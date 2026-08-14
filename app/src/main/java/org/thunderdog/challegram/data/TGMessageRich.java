/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 13/08/2026
 */
package org.thunderdog.challegram.data;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.DoubleImageReceiver;
import org.thunderdog.challegram.loader.ImageReceiver;
import org.thunderdog.challegram.loader.Receiver;
import org.thunderdog.challegram.loader.gif.GifReceiver;
import org.thunderdog.challegram.mediaview.MediaViewController;
import org.thunderdog.challegram.mediaview.data.MediaItem;
import org.thunderdog.challegram.mediaview.data.MediaStack;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.TextWrapper;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.ColorUtils;

// Rich messages (PageBlock-based posts): media blocks rendered as a swipeable
// carousel (one page per media, dot indicator), the flattened block text below.
// Tapping the text opens the full post through the native Instant View engine
// (tables, slideshows, embeds and block order all render there) via a
// synthetic instant view page
public class TGMessageRich extends TGMessage implements MediaWrapper.OnClickListener, me.vkryl.android.util.ClickHelper.Delegate {
  private static final int ANIMATOR_PAGER = 0;
  private static final float PAGER_MIN_FLING_DP = 400f; // dp per second

  private final TdApi.RichMessage richMessage;
  private final ArrayList<TdApi.PageBlock> mediaBlocks;
  private final ArrayList<MediaWrapper> wrappers = new ArrayList<>();
  private final me.vkryl.android.util.ClickHelper clickHelper = new me.vkryl.android.util.ClickHelper(this);
  private final RectF indicatorRect = new RectF();
  private boolean contentInited;
  private TextWrapper text;

  private int pagerWidth, pagerHeight;
  private int[] pageCellWidths, pageCellHeights;
  private float pagerScrollX;
  private float pagerSnapFrom, pagerSnapTo;
  private @Nullable FactorAnimator pagerAnimator;

  private boolean pagerTouchActive, pagerDragging;
  private float pagerTouchStartX, pagerTouchStartY, pagerStartScroll;
  private @Nullable ViewParent pagerCaughtParent;
  private @Nullable VelocityTracker pagerVelocityTracker;

  public static ArrayList<TdApi.PageBlock> collectMediaBlocks (TdApi.RichMessage richMessage) {
    ArrayList<TdApi.PageBlock> result = new ArrayList<>();
    if (richMessage.blocks != null) {
      for (TdApi.PageBlock block : richMessage.blocks) {
        collectMediaBlocks(result, block);
      }
    }
    return result;
  }

  private static void collectMediaBlocks (List<TdApi.PageBlock> out, TdApi.PageBlock block) {
    switch (block.getConstructor()) {
      case TdApi.PageBlockPhoto.CONSTRUCTOR:
        if (((TdApi.PageBlockPhoto) block).photo != null) {
          out.add(block);
        }
        break;
      case TdApi.PageBlockVideo.CONSTRUCTOR:
        if (((TdApi.PageBlockVideo) block).video != null) {
          out.add(block);
        }
        break;
      case TdApi.PageBlockAnimation.CONSTRUCTOR:
        if (((TdApi.PageBlockAnimation) block).animation != null) {
          out.add(block);
        }
        break;
      case TdApi.PageBlockCover.CONSTRUCTOR:
        collectMediaBlocks(out, ((TdApi.PageBlockCover) block).cover);
        break;
      case TdApi.PageBlockCollage.CONSTRUCTOR: {
        TdApi.PageBlockCollage collage = (TdApi.PageBlockCollage) block;
        if (collage.blocks != null) {
          for (TdApi.PageBlock innerBlock : collage.blocks) {
            collectMediaBlocks(out, innerBlock);
          }
        }
        break;
      }
      case TdApi.PageBlockSlideshow.CONSTRUCTOR: {
        TdApi.PageBlockSlideshow slideshow = (TdApi.PageBlockSlideshow) block;
        if (slideshow.blocks != null) {
          for (TdApi.PageBlock innerBlock : slideshow.blocks) {
            collectMediaBlocks(out, innerBlock);
          }
        }
        break;
      }
      case TdApi.PageBlockDetails.CONSTRUCTOR: {
        TdApi.PageBlockDetails details = (TdApi.PageBlockDetails) block;
        if (details.blocks != null) {
          for (TdApi.PageBlock innerBlock : details.blocks) {
            collectMediaBlocks(out, innerBlock);
          }
        }
        break;
      }
      default:
        break;
    }
  }

  public TGMessageRich (MessagesManager manager, TdApi.Message msg, @NonNull TdApi.RichMessage richMessage, @NonNull ArrayList<TdApi.PageBlock> mediaBlocks) {
    super(manager, msg);
    this.richMessage = richMessage;
    this.mediaBlocks = mediaBlocks;
  }

  private @Nullable MediaWrapper newMediaWrapper (TdApi.PageBlock block) {
    switch (block.getConstructor()) {
      case TdApi.PageBlockPhoto.CONSTRUCTOR:
        return new MediaWrapper(context(), tdlib, ((TdApi.PageBlockPhoto) block).photo, msg.chatId, msg.id, this, false);
      case TdApi.PageBlockVideo.CONSTRUCTOR:
        return new MediaWrapper(context(), tdlib, ((TdApi.PageBlockVideo) block).video, null, msg.chatId, msg.id, this, false);
      case TdApi.PageBlockAnimation.CONSTRUCTOR:
        return new MediaWrapper(context(), tdlib, ((TdApi.PageBlockAnimation) block).animation, msg.chatId, msg.id, this, false);
    }
    return null;
  }

  @Override
  protected void buildContent (int maxWidth) {
    if (!contentInited) {
      contentInited = true;
      for (TdApi.PageBlock block : mediaBlocks) {
        MediaWrapper wrapper = newMediaWrapper(block);
        if (wrapper == null) {
          continue;
        }
        wrapper.setViewProvider(currentViews);
        wrapper.setOnClickListener(this);
        wrapper.setNeedRound(true, true, true, true);
        wrappers.add(wrapper);
      }
      TdApi.FormattedText flatText = TD.textFromRichMessage(richMessage, false);
      if (!flatText.text.isEmpty()) {
        this.text = new TextWrapper(tdlib, flatText, getTextStyleProvider(), getTextColorSet(), openParameters(), null)
          .setViewProvider(currentViews);
      }
    }
    if (!wrappers.isEmpty()) {
      pagerWidth = maxWidth;
      int maxHeight = Math.max(Screen.dp(120f), (int) (maxWidth * 1.2f));
      // uniform page frame: the tallest media scaled to full width, clamped
      int height = 0;
      for (MediaWrapper wrapper : wrappers) {
        int contentWidth = wrapper.getContentWidth();
        int contentHeight = wrapper.getContentHeight();
        if (contentWidth > 0 && contentHeight > 0) {
          height = Math.max(height, (int) ((float) contentHeight * maxWidth / contentWidth));
        }
      }
      pagerHeight = Math.max(Screen.dp(120f), Math.min(maxHeight, height == 0 ? maxHeight : height));
      // each media fits centered inside the frame
      pageCellWidths = new int[wrappers.size()];
      pageCellHeights = new int[wrappers.size()];
      for (int i = 0; i < wrappers.size(); i++) {
        MediaWrapper wrapper = wrappers.get(i);
        int contentWidth = wrapper.getContentWidth();
        int contentHeight = wrapper.getContentHeight();
        if (contentWidth > 0 && contentHeight > 0) {
          float scale = Math.min((float) pagerWidth / contentWidth, (float) pagerHeight / contentHeight);
          pageCellWidths[i] = Math.max(1, (int) (contentWidth * scale));
          pageCellHeights[i] = Math.max(1, (int) (contentHeight * scale));
        } else {
          pageCellWidths[i] = pagerWidth;
          pageCellHeights[i] = pagerHeight;
        }
      }
      pagerScrollX = clampPagerScroll(pagerScrollX);
    }
    if (text != null) {
      text.prepare(maxWidth);
    }
  }

  private int pageStride () {
    return pagerWidth + Screen.dp(6f);
  }

  private float clampPagerScroll (float scroll) {
    float max = (float) pageStride() * (wrappers.size() - 1);
    return Math.max(0f, Math.min(scroll, Math.max(0f, max)));
  }

  private int currentPageIndex () {
    if (wrappers.size() <= 1) {
      return 0;
    }
    int page = Math.round(pagerScrollX / (float) pageStride());
    return Math.max(0, Math.min(wrappers.size() - 1, page));
  }

  @Override
  protected int getContentWidth () {
    int width = wrappers.isEmpty() ? 0 : pagerWidth;
    if (text != null) {
      width = Math.max(width, text.getWidth());
    }
    return width;
  }

  @Override
  protected int getContentHeight () {
    int height = 0;
    if (!wrappers.isEmpty()) {
      height += pagerHeight;
    }
    if (text != null) {
      if (height > 0) {
        height += Screen.dp(10f);
      }
      height += text.getHeight();
    }
    if (useBubbles()) {
      height += Screen.dp(4f);
    }
    return height;
  }

  @Override
  public boolean needComplexReceiver () {
    return true;
  }

  @Override
  public void requestMediaContent (ComplexReceiver receiver, boolean invalidate, int invalidateArg) {
    // Mirrors MosaicWrapper.requestFiles: cache receiver references on the
    // wrappers, draw through the cached references
    for (int i = 0; i < wrappers.size(); i++) {
      MediaWrapper wrapper = wrappers.get(i);
      DoubleImageReceiver preview = receiver.getPreviewReceiver(i);
      if (!invalidate || wrapper.showPreview()) {
        wrapper.requestPreview(preview);
      }
      wrapper.setPreviewReceiverReference(preview);
      Receiver target;
      if (wrapper.needGif()) {
        GifReceiver gifReceiver = receiver.getGifReceiver(i);
        wrapper.requestGif(gifReceiver);
        target = gifReceiver;
      } else {
        ImageReceiver imageReceiver = receiver.getImageReceiver(i);
        wrapper.requestImage(imageReceiver);
        target = imageReceiver;
      }
      wrapper.setTargetReceiverReference(target);
    }
    receiver.clearReceiversWithHigherKey(wrappers.size());
  }

  @Override
  protected void drawContent (MessageView view, Canvas c, int startX, int startY, int maxWidth, ComplexReceiver receiver) {
    int y = startY;
    if (!wrappers.isEmpty()) {
      int stride = pageStride();
      int scroll = Math.round(pagerScrollX);
      c.save();
      c.clipRect(startX, y, startX + pagerWidth, y + pagerHeight);
      for (int i = 0; i < wrappers.size(); i++) {
        int pageX = startX + i * stride - scroll;
        if (pageX >= startX + pagerWidth || pageX + pagerWidth <= startX) {
          continue;
        }
        MediaWrapper wrapper = wrappers.get(i);
        DoubleImageReceiver preview = wrapper.getPreviewReceiverReference();
        Receiver target = wrapper.getTargetReceiverReference();
        if (preview == null || target == null) {
          // receivers not requested yet - same guard as MosaicWrapper.draw
          continue;
        }
        int cellWidth = pageCellWidths[i];
        int cellHeight = pageCellHeights[i];
        wrapper.buildContent(cellWidth, cellHeight);
        wrapper.draw(view, c, pageX + (pagerWidth - cellWidth) / 2, y + (pagerHeight - cellHeight) / 2, preview, target, 1f);
      }
      c.restore();
      if (wrappers.size() > 1) {
        drawPageIndicator(c, startX, y);
      }
      y += pagerHeight + Screen.dp(10f);
    }
    if (text != null) {
      text.draw(c, startX, startX + maxWidth, 0, y, null, 1f, view.getTextMediaReceiver());
    }
  }

  private void drawPageIndicator (Canvas c, int startX, int startY) {
    int count = wrappers.size();
    if (count > 10) {
      // too many dots - counter chip in the corner, duration-badge style
      String counter = (currentPageIndex() + 1) + "/" + count;
      TextPaint paint = Paints.whiteMediumPaint(13f, false, false);
      float textWidth = U.measureText(counter, paint);
      float right = startX + pagerWidth - Screen.dp(8f);
      float top = startY + Screen.dp(8f);
      float textX = right - Screen.dp(4f) - textWidth;
      indicatorRect.set(textX - Screen.dp(4f), top, right, top + Screen.dp(20f));
      c.drawRoundRect(indicatorRect, Screen.dp(4f), Screen.dp(4f), Paints.fillingPaint(0x4c000000));
      c.drawText(counter, textX, top + Screen.dp(15f), paint);
    } else {
      float radius = Screen.dp(2.5f);
      float spacing = Screen.dp(9f);
      float rowWidth = spacing * (count - 1);
      float firstCenterX = startX + pagerWidth / 2f - rowWidth / 2f;
      float centerY = startY + pagerHeight - Screen.dp(12f);
      float chipRadius = Screen.dp(7.5f);
      indicatorRect.set(firstCenterX - Screen.dp(8f), centerY - chipRadius, firstCenterX + rowWidth + Screen.dp(8f), centerY + chipRadius);
      c.drawRoundRect(indicatorRect, chipRadius, chipRadius, Paints.fillingPaint(0x4c000000));
      float positionFactor = pagerScrollX / (float) pageStride();
      for (int i = 0; i < count; i++) {
        float factor = Math.max(0f, Math.min(1f, 1f - Math.abs(positionFactor - i)));
        c.drawCircle(firstCenterX + spacing * i, centerY, radius, Paints.fillingPaint(ColorUtils.color((int) (255f * (.5f + .5f * factor)), 0xffffff)));
      }
    }
  }

  // Carousel gesture: claims the parent on touch-down over the pager
  // (FileComponent seek precedent - the only way to beat swipe-to-reply, whose
  // check in MessageView runs before the message gets the move event), then
  // releases it if the gesture turns out vertical so the list can scroll

  private boolean isInsidePager (float x, float y) {
    if (wrappers.isEmpty()) {
      return false;
    }
    int left = getContentX();
    int top = getContentY();
    return x >= left && x <= left + pagerWidth && y >= top && y <= top + pagerHeight;
  }

  private void dropPagerTouch () {
    pagerTouchActive = false;
    pagerDragging = false;
    if (pagerCaughtParent != null) {
      pagerCaughtParent.requestDisallowInterceptTouchEvent(false);
      pagerCaughtParent = null;
    }
    if (pagerVelocityTracker != null) {
      pagerVelocityTracker.recycle();
      pagerVelocityTracker = null;
    }
  }

  private int findSnapTarget (float velocityX) {
    int stride = pageStride();
    int target;
    if (Math.abs(velocityX) >= Screen.dp(PAGER_MIN_FLING_DP)) {
      if (velocityX < 0) {
        target = (int) Math.floor(pagerScrollX / stride) + 1;
      } else {
        target = (int) Math.ceil(pagerScrollX / stride) - 1;
      }
    } else {
      target = Math.round(pagerScrollX / (float) stride);
    }
    return Math.max(0, Math.min(wrappers.size() - 1, target));
  }

  private void snapToPage (int page) {
    float target = clampPagerScroll((float) page * pageStride());
    if (target == pagerScrollX) {
      return;
    }
    pagerSnapFrom = pagerScrollX;
    pagerSnapTo = target;
    if (pagerAnimator == null) {
      pagerAnimator = new FactorAnimator(ANIMATOR_PAGER, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l);
    }
    pagerAnimator.forceFactor(0f);
    pagerAnimator.animateTo(1f);
  }

  @Override
  protected void onChildFactorChanged (int id, float factor, float fraction) {
    if (id == ANIMATOR_PAGER) {
      pagerScrollX = pagerSnapFrom + (pagerSnapTo - pagerSnapFrom) * factor;
      invalidate();
    }
  }

  private boolean pagerOnTouchEvent (MessageView view, MotionEvent e) {
    switch (e.getAction()) {
      case MotionEvent.ACTION_DOWN: {
        pagerTouchActive = false;
        if (wrappers.size() > 1 && isInsidePager(e.getX(), e.getY())) {
          pagerTouchActive = true;
          pagerDragging = false;
          pagerTouchStartX = e.getX();
          pagerTouchStartY = e.getY();
          if (pagerAnimator != null && pagerAnimator.isAnimating()) {
            // freeze the snap mid-flight so the finger catches the pager
            pagerAnimator.forceFactor(pagerAnimator.getFactor());
          }
          pagerStartScroll = pagerScrollX;
          pagerCaughtParent = view.getParent();
          if (pagerCaughtParent != null) {
            pagerCaughtParent.requestDisallowInterceptTouchEvent(true);
          }
          if (pagerVelocityTracker == null) {
            pagerVelocityTracker = VelocityTracker.obtain();
          } else {
            pagerVelocityTracker.clear();
          }
          pagerVelocityTracker.addMovement(e);
        }
        // fall through to the tap chain, which claims the touch stream
        return false;
      }
      case MotionEvent.ACTION_MOVE: {
        if (!pagerTouchActive) {
          return false;
        }
        if (pagerVelocityTracker != null) {
          pagerVelocityTracker.addMovement(e);
        }
        float dx = e.getX() - pagerTouchStartX;
        float dy = e.getY() - pagerTouchStartY;
        if (!pagerDragging) {
          float slop = Screen.getTouchSlop();
          if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
            dropPagerTouch();
            return false;
          }
          if (Math.abs(dx) > slop) {
            pagerDragging = true;
          }
        }
        if (pagerDragging) {
          pagerScrollX = clampPagerScroll(pagerStartScroll + (pagerTouchStartX - e.getX()));
          invalidate();
          return true;
        }
        return false;
      }
      case MotionEvent.ACTION_UP: {
        if (!pagerTouchActive) {
          return false;
        }
        boolean wasDragging = pagerDragging;
        float velocityX = 0f;
        if (pagerVelocityTracker != null) {
          pagerVelocityTracker.addMovement(e);
          pagerVelocityTracker.computeCurrentVelocity(1000);
          velocityX = pagerVelocityTracker.getXVelocity();
        }
        dropPagerTouch();
        if (wasDragging) {
          snapToPage(findSnapTarget(velocityX));
          return true;
        }
        return false;
      }
      case MotionEvent.ACTION_CANCEL: {
        if (!pagerTouchActive) {
          return false;
        }
        boolean wasDragging = pagerDragging;
        dropPagerTouch();
        if (wasDragging) {
          snapToPage(currentPageIndex());
        }
        return false;
      }
    }
    return false;
  }

  @Override
  public boolean onTouchEvent (MessageView view, MotionEvent e) {
    if (pagerOnTouchEvent(view, e)) {
      return true;
    }
    if (super.onTouchEvent(view, e)) {
      return true;
    }
    if (text != null && text.onTouchEvent(view, e)) {
      return true;
    }
    return clickHelper.onTouchEvent(view, e);
  }

  // Taps: the current carousel page opens the viewer, the text area opens the
  // full post in the Instant View engine

  @Override
  public boolean needClickAt (View view, float x, float y) {
    if (isInsidePager(x, y)) {
      return true;
    }
    if (text == null) {
      return false;
    }
    float contentY = y - getContentY();
    float textTop = !wrappers.isEmpty() ? pagerHeight + Screen.dp(10f) : 0;
    return contentY >= textTop && contentY <= textTop + text.getHeight();
  }

  @Override
  public void onClickAt (View view, float x, float y) {
    if (isInsidePager(x, y)) {
      onClick(view, wrappers.get(currentPageIndex()));
    } else {
      openFullView();
    }
  }

  private boolean openingFullView;

  private void openFullView () {
    if (openingFullView) {
      return;
    }
    if (richMessage.isFull) {
      showInstantView(richMessage);
      return;
    }
    openingFullView = true;
    tdlib.client().send(new TdApi.GetFullRichMessage(msg.chatId, msg.id), result -> runOnUiThreadOptional(() -> {
      openingFullView = false;
      showInstantView(result.getConstructor() == TdApi.RichMessage.CONSTRUCTOR ? (TdApi.RichMessage) result : richMessage);
    }));
  }

  private void showInstantView (TdApi.RichMessage rich) {
    // Synthetic page: the native Instant View engine renders every block type.
    // The controller only reads url/displayUrl/siteName from the link preview
    TdApi.WebPageInstantView instantView = new TdApi.WebPageInstantView(rich.blocks, 0, 2, rich.isRtl, true, null);
    TdApi.LinkPreview linkPreview = new TdApi.LinkPreview();
    linkPreview.url = "";
    linkPreview.displayUrl = "";
    linkPreview.siteName = tdlib.senderName(msg.senderId, true);
    org.thunderdog.challegram.ui.InstantViewController controller = new org.thunderdog.challegram.ui.InstantViewController(context(), tdlib);
    controller.setArguments(new org.thunderdog.challegram.ui.InstantViewController.Args(linkPreview, instantView, null));
    controller.show();
  }

  @Override
  public boolean performLongPress (View view, float x, float y) {
    boolean result = super.performLongPress(view, x, y);
    if (!wrappers.isEmpty()) {
      result = wrappers.get(currentPageIndex()).performLongPress(view) || result;
    }
    return result;
  }

  @Override
  public boolean onClick (View view, MediaWrapper clickWrapper) {
    // Open the whole media set in the viewer, positioned at the tapped item
    MediaStack stack = new MediaStack(context(), tdlib);
    List<MediaItem> items = new ArrayList<>();
    int foundIndex = -1;
    for (TdApi.PageBlock block : mediaBlocks) {
      MediaItem item = null;
      switch (block.getConstructor()) {
        case TdApi.PageBlockPhoto.CONSTRUCTOR:
          item = MediaItem.valueOf(context(), tdlib, ((TdApi.PageBlockPhoto) block).photo, null);
          break;
        case TdApi.PageBlockVideo.CONSTRUCTOR:
          item = MediaItem.valueOf(context(), tdlib, ((TdApi.PageBlockVideo) block).video, null, null, null);
          break;
        case TdApi.PageBlockAnimation.CONSTRUCTOR:
          item = MediaItem.valueOf(context(), tdlib, ((TdApi.PageBlockAnimation) block).animation, null);
          break;
      }
      if (item != null) {
        items.add(item);
      }
    }
    if (items.isEmpty()) {
      return false;
    }
    // wrappers and items share the ordering of mediaBlocks - indexOf maps tap to page
    foundIndex = clickWrapper != null ? wrappers.indexOf(clickWrapper) : -1;
    if (foundIndex < 0 || foundIndex >= items.size()) {
      foundIndex = 0;
    }
    stack.set(foundIndex, items);
    MediaViewController.openWithStack(controller(), stack, null, null, false);
    return true;
  }

  @Override
  protected boolean isSupportedMessageContent (TdApi.Message message, TdApi.MessageContent messageContent) {
    // Edits rebuild the whole message through valueOf
    return false;
  }

  @Override
  public void autoDownloadContent (TdApi.ChatType type) {
    for (MediaWrapper wrapper : wrappers) {
      wrapper.getFileProgress().downloadAutomatically(type);
    }
  }

  @Override
  protected void onMessageContainerDestroyed () {
    for (MediaWrapper wrapper : wrappers) {
      wrapper.destroy();
    }
    dropPagerTouch();
  }
}
