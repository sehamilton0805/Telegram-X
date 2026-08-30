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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.StringUtils;

// Rich messages (PageBlock-based posts) rendered in document order, like the
// official client: text runs and media segments interleave as authored. A
// slideshow becomes a swipeable carousel segment (dot indicator, own pager
// state), a collage becomes a tiled mosaic, a standalone photo/video an
// inline single-media segment; media captions render right below their block
// and a button row becomes a real inline keyboard. Tapping text opens the
// full post through the native Instant View engine via a synthetic page
public class TGMessageRich extends TGMessage implements MediaWrapper.OnClickListener, me.vkryl.android.util.ClickHelper.Delegate {
  private static final float PAGER_MIN_FLING_DP = 400f; // dp per second
  private static final float PART_SPACING_DP = 10f;

  private final TdApi.RichMessage richMessage;
  private final ArrayList<TdApi.PageBlock> mediaBlocks;
  // Global wrapper list, in document order: the viewer stack and
  // auto-download index it; receiver keys are per-wrapper tdlib file ids
  private final ArrayList<MediaWrapper> wrappers = new ArrayList<>();
  private final ArrayList<Part> parts = new ArrayList<>();
  private final me.vkryl.android.util.ClickHelper clickHelper = new me.vkryl.android.util.ClickHelper(this);
  private boolean contentInited;

  private abstract static class Part {
    int y, height;
  }

  private static class TextPart extends Part {
    final TextWrapper wrapper;

    TextPart (TextWrapper wrapper) {
      this.wrapper = wrapper;
    }
  }

  private class MediaPart extends Part implements FactorAnimator.Target {
    final ArrayList<MediaWrapper> pageWrappers = new ArrayList<>();
    final RectF indicatorRect = new RectF();
    int pagerWidth, pagerHeight;
    int[] cellWidths, cellHeights;
    float scrollX, snapFrom, snapTo;
    @Nullable FactorAnimator animator;

    int pageStride () {
      return pagerWidth + Screen.dp(6f);
    }

    float clampScroll (float scroll) {
      float max = (float) pageStride() * (pageWrappers.size() - 1);
      return Math.max(0f, Math.min(scroll, Math.max(0f, max)));
    }

    int currentPageIndex () {
      if (pageWrappers.size() <= 1) {
        return 0;
      }
      int page = Math.round(scrollX / (float) pageStride());
      return Math.max(0, Math.min(pageWrappers.size() - 1, page));
    }

    void snapToPage (int page) {
      float target = clampScroll((float) page * pageStride());
      if (target == scrollX) {
        return;
      }
      snapFrom = scrollX;
      snapTo = target;
      if (animator == null) {
        animator = new FactorAnimator(0, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l);
      }
      animator.forceFactor(0f);
      animator.animateTo(1f);
    }

    @Override
    public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
      scrollX = snapFrom + (snapTo - snapFrom) * factor;
      invalidate();
    }
  }

  private class MosaicPart extends Part {
    final MosaicWrapper mosaic;
    final ArrayList<MediaWrapper> tileWrappers = new ArrayList<>();

    MosaicPart (MosaicWrapper mosaic) {
      this.mosaic = mosaic;
    }
  }

  private class ButtonRowPart extends Part {
    final TGInlineKeyboard keyboard;
    final TdApi.ReplyMarkupInlineKeyboard markup;

    ButtonRowPart (TdApi.InlineButton[] buttons) {
      TdApi.InlineKeyboardButton[] row = new TdApi.InlineKeyboardButton[buttons.length];
      for (int i = 0; i < buttons.length; i++) {
        TdApi.InlineButton button = buttons[i];
        String label = TD.richTextToString(button.text);
        row[i] = new TdApi.InlineKeyboardButton(StringUtils.isEmpty(label) ? " " : label, 0, button.style, button.type);
      }
      this.markup = new TdApi.ReplyMarkupInlineKeyboard(new TdApi.InlineKeyboardButton[][] {row}, false);
      this.keyboard = new TGInlineKeyboard(TGMessageRich.this, false);
      this.keyboard.setViewProvider(currentViews);
    }
  }

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

  private static @Nullable TdApi.PageBlockCaption captionOf (TdApi.PageBlock block) {
    switch (block.getConstructor()) {
      case TdApi.PageBlockPhoto.CONSTRUCTOR:
        return ((TdApi.PageBlockPhoto) block).caption;
      case TdApi.PageBlockVideo.CONSTRUCTOR:
        return ((TdApi.PageBlockVideo) block).caption;
      case TdApi.PageBlockAnimation.CONSTRUCTOR:
        return ((TdApi.PageBlockAnimation) block).caption;
      case TdApi.PageBlockCollage.CONSTRUCTOR:
        return ((TdApi.PageBlockCollage) block).caption;
      case TdApi.PageBlockSlideshow.CONSTRUCTOR:
        return ((TdApi.PageBlockSlideshow) block).caption;
    }
    return null;
  }

  // Document-order segmentation: consecutive non-media blocks accumulate into
  // one text run; every media block flushes the run and becomes its own
  // segment - slideshow as carousel, collage as tiled mosaic, standalone
  // media as single-page segment, button row as an inline keyboard. The
  // media traversal mirrors collectMediaBlocks exactly, so the global
  // wrapper order matches mediaBlocks and the viewer mapping stays intact

  private void buildParts () {
    ArrayList<TdApi.PageBlock> textRun = new ArrayList<>();
    if (richMessage.blocks != null) {
      for (TdApi.PageBlock block : richMessage.blocks) {
        segmentBlock(block, textRun);
      }
    }
    flushTextRun(textRun);
  }

  private void flushTextRun (ArrayList<TdApi.PageBlock> textRun) {
    if (textRun.isEmpty()) {
      return;
    }
    TdApi.FormattedText partText = TD.textFromPageBlocks(textRun);
    textRun.clear();
    if (partText.text.isEmpty()) {
      return;
    }
    addTextPart(partText);
  }

  private void addTextPart (TdApi.FormattedText text) {
    TextWrapper wrapper = new TextWrapper(tdlib, text, getTextStyleProvider(), getTextColorSet(), openParameters(), null)
      .setViewProvider(currentViews);
    parts.add(new TextPart(wrapper));
  }

  private void addCaptionPart (@Nullable TdApi.PageBlockCaption caption) {
    if (caption == null) {
      return;
    }
    StringBuilder b = new StringBuilder();
    String text = TD.richTextToString(caption.text);
    String credit = TD.richTextToString(caption.credit);
    if (!StringUtils.isEmpty(text) && !text.trim().isEmpty()) {
      b.append(text.trim());
    }
    if (!StringUtils.isEmpty(credit) && !credit.trim().isEmpty()) {
      if (b.length() > 0) {
        b.append('\n');
      }
      b.append(credit.trim());
    }
    if (b.length() > 0) {
      addTextPart(new TdApi.FormattedText(b.toString(), null));
    }
  }

  private void segmentBlock (TdApi.PageBlock block, ArrayList<TdApi.PageBlock> textRun) {
    switch (block.getConstructor()) {
      case TdApi.PageBlockPhoto.CONSTRUCTOR:
        if (((TdApi.PageBlockPhoto) block).photo != null) {
          flushTextRun(textRun);
          addCarouselPart(Collections.singletonList(block));
          addCaptionPart(captionOf(block));
        }
        break;
      case TdApi.PageBlockVideo.CONSTRUCTOR:
        if (((TdApi.PageBlockVideo) block).video != null) {
          flushTextRun(textRun);
          addCarouselPart(Collections.singletonList(block));
          addCaptionPart(captionOf(block));
        }
        break;
      case TdApi.PageBlockAnimation.CONSTRUCTOR:
        if (((TdApi.PageBlockAnimation) block).animation != null) {
          flushTextRun(textRun);
          addCarouselPart(Collections.singletonList(block));
          addCaptionPart(captionOf(block));
        }
        break;
      case TdApi.PageBlockCover.CONSTRUCTOR:
        segmentBlock(((TdApi.PageBlockCover) block).cover, textRun);
        break;
      case TdApi.PageBlockCollage.CONSTRUCTOR: {
        ArrayList<TdApi.PageBlock> groupMedia = new ArrayList<>();
        collectMediaBlocks(groupMedia, block);
        if (!groupMedia.isEmpty()) {
          flushTextRun(textRun);
          addMosaicPart(groupMedia);
          addCaptionPart(captionOf(block));
        }
        break;
      }
      case TdApi.PageBlockSlideshow.CONSTRUCTOR: {
        ArrayList<TdApi.PageBlock> groupMedia = new ArrayList<>();
        collectMediaBlocks(groupMedia, block);
        if (!groupMedia.isEmpty()) {
          flushTextRun(textRun);
          addCarouselPart(groupMedia);
          addCaptionPart(captionOf(block));
        }
        break;
      }
      case TdApi.PageBlockButtonRow.CONSTRUCTOR: {
        TdApi.PageBlockButtonRow buttonRow = (TdApi.PageBlockButtonRow) block;
        if (buttonRow.buttons != null && buttonRow.buttons.length > 0) {
          flushTextRun(textRun);
          parts.add(new ButtonRowPart(buttonRow.buttons));
        }
        break;
      }
      case TdApi.PageBlockDetails.CONSTRUCTOR: {
        TdApi.PageBlockDetails details = (TdApi.PageBlockDetails) block;
        if (details.blocks != null) {
          for (TdApi.PageBlock innerBlock : details.blocks) {
            segmentBlock(innerBlock, textRun);
          }
        }
        break;
      }
      default:
        textRun.add(block);
        break;
    }
  }

  private @Nullable MediaWrapper registerWrapper (TdApi.PageBlock mediaBlock) {
    MediaWrapper wrapper = newMediaWrapper(mediaBlock);
    if (wrapper == null) {
      return null;
    }
    wrapper.setViewProvider(currentViews);
    wrapper.setOnClickListener(this);
    wrappers.add(wrapper);
    return wrapper;
  }

  private void addCarouselPart (List<TdApi.PageBlock> blocks) {
    MediaPart part = new MediaPart();
    for (TdApi.PageBlock mediaBlock : blocks) {
      MediaWrapper wrapper = registerWrapper(mediaBlock);
      if (wrapper != null) {
        wrapper.setNeedRound(true, true, true, true);
        part.pageWrappers.add(wrapper);
      }
    }
    if (!part.pageWrappers.isEmpty()) {
      parts.add(part);
    }
  }

  private void addMosaicPart (List<TdApi.PageBlock> blocks) {
    MosaicPart part = null;
    for (TdApi.PageBlock mediaBlock : blocks) {
      MediaWrapper wrapper = registerWrapper(mediaBlock);
      if (wrapper == null) {
        continue;
      }
      if (part == null) {
        part = new MosaicPart(new MosaicWrapper(wrapper, this));
      } else {
        part.mosaic.addItem(wrapper, true);
      }
      part.tileWrappers.add(wrapper);
    }
    if (part != null) {
      parts.add(part);
    }
  }

  @Override
  protected void buildContent (int maxWidth) {
    if (!contentInited) {
      contentInited = true;
      buildParts();
    }
    int y = 0;
    boolean first = true;
    for (Part part : parts) {
      if (!first) {
        y += Screen.dp(PART_SPACING_DP);
      }
      first = false;
      part.y = y;
      if (part instanceof TextPart) {
        TextWrapper wrapper = ((TextPart) part).wrapper;
        wrapper.prepare(maxWidth);
        part.height = wrapper.getHeight();
      } else if (part instanceof MediaPart) {
        MediaPart mediaPart = (MediaPart) part;
        layoutMediaPart(mediaPart, maxWidth);
        part.height = mediaPart.pagerHeight;
      } else if (part instanceof MosaicPart) {
        MosaicPart mosaicPart = (MosaicPart) part;
        int maxHeight = Math.max(Screen.dp(120f), (int) (maxWidth * 1.2f));
        mosaicPart.mosaic.build(maxWidth, maxHeight, Screen.dp(120f), Screen.dp(120f), MosaicWrapper.MODE_FIT_WIDTH, false);
        part.height = mosaicPart.mosaic.getHeight();
      } else if (part instanceof ButtonRowPart) {
        ButtonRowPart buttonPart = (ButtonRowPart) part;
        buttonPart.keyboard.set(msg.id, buttonPart.markup, maxWidth, maxWidth);
        part.height = buttonPart.keyboard.getHeight();
      }
      y += part.height;
    }
  }

  private void layoutMediaPart (MediaPart part, int maxWidth) {
    part.pagerWidth = maxWidth;
    int maxHeight = Math.max(Screen.dp(120f), (int) (maxWidth * 1.2f));
    // uniform page frame: the tallest media scaled to full width, clamped
    int height = 0;
    for (MediaWrapper wrapper : part.pageWrappers) {
      int contentWidth = wrapper.getContentWidth();
      int contentHeight = wrapper.getContentHeight();
      if (contentWidth > 0 && contentHeight > 0) {
        height = Math.max(height, (int) ((float) contentHeight * maxWidth / contentWidth));
      }
    }
    part.pagerHeight = Math.max(Screen.dp(120f), Math.min(maxHeight, height == 0 ? maxHeight : height));
    // each media fits centered inside the frame
    part.cellWidths = new int[part.pageWrappers.size()];
    part.cellHeights = new int[part.pageWrappers.size()];
    for (int i = 0; i < part.pageWrappers.size(); i++) {
      MediaWrapper wrapper = part.pageWrappers.get(i);
      int contentWidth = wrapper.getContentWidth();
      int contentHeight = wrapper.getContentHeight();
      if (contentWidth > 0 && contentHeight > 0) {
        float scale = Math.min((float) part.pagerWidth / contentWidth, (float) part.pagerHeight / contentHeight);
        part.cellWidths[i] = Math.max(1, (int) (contentWidth * scale));
        part.cellHeights[i] = Math.max(1, (int) (contentHeight * scale));
      } else {
        part.cellWidths[i] = part.pagerWidth;
        part.cellHeights[i] = part.pagerHeight;
      }
    }
    part.scrollX = part.clampScroll(part.scrollX);
  }

  @Override
  protected int getContentWidth () {
    int width = 0;
    for (Part part : parts) {
      if (part instanceof TextPart) {
        width = Math.max(width, ((TextPart) part).wrapper.getWidth());
      } else if (part instanceof MediaPart) {
        width = Math.max(width, ((MediaPart) part).pagerWidth);
      } else if (part instanceof MosaicPart) {
        width = Math.max(width, ((MosaicPart) part).mosaic.getWidth());
      } else if (part instanceof ButtonRowPart) {
        width = Math.max(width, ((ButtonRowPart) part).keyboard.getWidth());
      }
    }
    return width;
  }

  @Override
  protected int getContentHeight () {
    int height = 0;
    if (!parts.isEmpty()) {
      Part last = parts.get(parts.size() - 1);
      height = last.y + last.height;
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
    // Receivers are keyed by the wrapper's tdlib file id (MosaicWrapper's own
    // scheme), one shared keyspace for carousel, mosaic and single segments;
    // references stay cached on the wrappers as the cross-view fallback
    final HashSet<Long> validKeys = new HashSet<>();
    for (MediaWrapper wrapper : wrappers) {
      long key = wrapper.getReceiverKey();
      validKeys.add(key);
      DoubleImageReceiver preview = receiver.getPreviewReceiver(key);
      if (!invalidate || wrapper.showPreview()) {
        wrapper.requestPreview(preview);
      }
      wrapper.setPreviewReceiverReference(preview);
      Receiver target;
      if (wrapper.needGif()) {
        GifReceiver gifReceiver = receiver.getGifReceiver(key);
        wrapper.requestGif(gifReceiver);
        target = gifReceiver;
      } else {
        ImageReceiver imageReceiver = receiver.getImageReceiver(key);
        wrapper.requestImage(imageReceiver);
        target = imageReceiver;
      }
      wrapper.setTargetReceiverReference(target);
    }
    receiver.clearReceivers((receiverType, unused, key) -> validKeys.contains(key));
  }

  @Override
  protected void drawContent (MessageView view, Canvas c, int startX, int startY, int maxWidth, ComplexReceiver receiver) {
    for (Part part : parts) {
      int top = startY + part.y;
      if (part instanceof TextPart) {
        ((TextPart) part).wrapper.draw(c, startX, startX + maxWidth, 0, top, null, 1f, view.getTextMediaReceiver());
      } else if (part instanceof MediaPart) {
        drawMediaPart((MediaPart) part, view, c, startX, top, receiver);
      } else if (part instanceof MosaicPart) {
        ((MosaicPart) part).mosaic.draw(view, c, startX, top, receiver, false);
      } else if (part instanceof ButtonRowPart) {
        ((ButtonRowPart) part).keyboard.draw(view, c, startX, top);
      }
    }
  }

  private void drawMediaPart (MediaPart part, MessageView view, Canvas c, int startX, int startY, ComplexReceiver receiver) {
    int stride = part.pageStride();
    int scroll = Math.round(part.scrollX);
    c.save();
    c.clipRect(startX, startY, startX + part.pagerWidth, startY + part.pagerHeight);
    for (int i = 0; i < part.pageWrappers.size(); i++) {
      int pageX = startX + i * stride - scroll;
      if (pageX >= startX + part.pagerWidth || pageX + part.pagerWidth <= startX) {
        continue;
      }
      MediaWrapper wrapper = part.pageWrappers.get(i);
      long receiverKey = wrapper.getReceiverKey();
      // Same cross-wiring hazard as MosaicWrapper.draw: the per-message
      // references are last-writer-wins across views, so draw through the
      // drawing view's own receivers, references as fallback
      DoubleImageReceiver refPreview = wrapper.getPreviewReceiverReference();
      Receiver refTarget = wrapper.getTargetReceiverReference();
      DoubleImageReceiver preview;
      Receiver target;
      if (receiver != null) {
        preview = receiver.getPreviewReceiver(receiverKey);
        target = wrapper.needGif() ? receiver.getGifReceiver(receiverKey) : receiver.getImageReceiver(receiverKey);
        if (target.isEmpty() && refTarget != null) {
          preview = refPreview;
          target = refTarget;
        }
      } else {
        preview = refPreview;
        target = refTarget;
      }
      if (preview == null || target == null) {
        continue;
      }
      int cellWidth = part.cellWidths[i];
      int cellHeight = part.cellHeights[i];
      wrapper.buildContent(cellWidth, cellHeight);
      wrapper.draw(view, c, pageX + (part.pagerWidth - cellWidth) / 2, startY + (part.pagerHeight - cellHeight) / 2, preview, target, 1f);
    }
    c.restore();
    if (part.pageWrappers.size() > 1) {
      drawPageIndicator(part, c, startX, startY);
    }
  }

  private void drawPageIndicator (MediaPart part, Canvas c, int startX, int startY) {
    int count = part.pageWrappers.size();
    if (count > 10) {
      // too many dots - counter chip in the corner, duration-badge style
      String counter = (part.currentPageIndex() + 1) + "/" + count;
      TextPaint paint = Paints.whiteMediumPaint(13f, false, false);
      float textWidth = U.measureText(counter, paint);
      float right = startX + part.pagerWidth - Screen.dp(8f);
      float top = startY + Screen.dp(8f);
      float textX = right - Screen.dp(4f) - textWidth;
      part.indicatorRect.set(textX - Screen.dp(4f), top, right, top + Screen.dp(20f));
      c.drawRoundRect(part.indicatorRect, Screen.dp(4f), Screen.dp(4f), Paints.fillingPaint(0x4c000000));
      c.drawText(counter, textX, top + Screen.dp(15f), paint);
    } else {
      float radius = Screen.dp(2.5f);
      float spacing = Screen.dp(9f);
      float rowWidth = spacing * (count - 1);
      float firstCenterX = startX + part.pagerWidth / 2f - rowWidth / 2f;
      float centerY = startY + part.pagerHeight - Screen.dp(12f);
      float chipRadius = Screen.dp(7.5f);
      part.indicatorRect.set(firstCenterX - Screen.dp(8f), centerY - chipRadius, firstCenterX + rowWidth + Screen.dp(8f), centerY + chipRadius);
      c.drawRoundRect(part.indicatorRect, chipRadius, chipRadius, Paints.fillingPaint(0x4c000000));
      float positionFactor = part.scrollX / (float) part.pageStride();
      for (int i = 0; i < count; i++) {
        float factor = Math.max(0f, Math.min(1f, 1f - Math.abs(positionFactor - i)));
        c.drawCircle(firstCenterX + spacing * i, centerY, radius, Paints.fillingPaint(ColorUtils.color((int) (255f * (.5f + .5f * factor)), 0xffffff)));
      }
    }
  }

  // Hit testing in content coordinates: parts stack vertically

  private @Nullable Part findPartAt (float x, float y) {
    int left = getContentX();
    int top = getContentY();
    float relY = y - top;
    for (Part part : parts) {
      if (relY >= part.y && relY < part.y + part.height) {
        if (part instanceof MediaPart) {
          return (x >= left && x <= left + ((MediaPart) part).pagerWidth) ? part : null;
        }
        if (part instanceof MosaicPart) {
          return (x >= left && x <= left + ((MosaicPart) part).mosaic.getWidth()) ? part : null;
        }
        return part;
      }
    }
    return null;
  }

  private @Nullable MediaPart findCarouselPartAt (float x, float y) {
    Part part = findPartAt(x, y);
    return part instanceof MediaPart ? (MediaPart) part : null;
  }

  private @Nullable MediaWrapper findTappedWrapper (Part part, float x, float y) {
    if (part instanceof MediaPart) {
      MediaPart mediaPart = (MediaPart) part;
      return mediaPart.pageWrappers.get(mediaPart.currentPageIndex());
    }
    if (part instanceof MosaicPart) {
      MosaicPart mosaicPart = (MosaicPart) part;
      for (MediaWrapper wrapper : mosaicPart.tileWrappers) {
        if (x >= wrapper.getCellLeft() && x <= wrapper.getCellRight() && y >= wrapper.getCellTop() && y <= wrapper.getCellBottom()) {
          return wrapper;
        }
      }
      return mosaicPart.tileWrappers.get(0);
    }
    return null;
  }

  // Carousel gesture: claims the parent on touch-down over a pager
  // (FileComponent seek precedent - the only way to beat swipe-to-reply, whose
  // check in MessageView runs before the message gets the move event), then
  // releases it if the gesture turns out vertical so the list can scroll

  private @Nullable MediaPart touchPart;
  private boolean pagerDragging;
  private float pagerTouchStartX, pagerTouchStartY, pagerStartScroll;
  private @Nullable ViewParent pagerCaughtParent;
  private @Nullable VelocityTracker pagerVelocityTracker;

  private void dropPagerTouch () {
    touchPart = null;
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

  private int findSnapTarget (MediaPart part, float velocityX) {
    int stride = part.pageStride();
    int target;
    if (Math.abs(velocityX) >= Screen.dp(PAGER_MIN_FLING_DP)) {
      if (velocityX < 0) {
        target = (int) Math.floor(part.scrollX / stride) + 1;
      } else {
        target = (int) Math.ceil(part.scrollX / stride) - 1;
      }
    } else {
      target = Math.round(part.scrollX / (float) stride);
    }
    return Math.max(0, Math.min(part.pageWrappers.size() - 1, target));
  }

  private boolean pagerOnTouchEvent (MessageView view, MotionEvent e) {
    switch (e.getAction()) {
      case MotionEvent.ACTION_DOWN: {
        touchPart = null;
        MediaPart part = findCarouselPartAt(e.getX(), e.getY());
        if (part != null && part.pageWrappers.size() > 1) {
          touchPart = part;
          pagerDragging = false;
          pagerTouchStartX = e.getX();
          pagerTouchStartY = e.getY();
          if (part.animator != null && part.animator.isAnimating()) {
            // freeze the snap mid-flight so the finger catches the pager
            part.animator.forceFactor(part.animator.getFactor());
          }
          pagerStartScroll = part.scrollX;
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
        MediaPart part = touchPart;
        if (part == null) {
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
          part.scrollX = part.clampScroll(pagerStartScroll + (pagerTouchStartX - e.getX()));
          invalidate();
          return true;
        }
        return false;
      }
      case MotionEvent.ACTION_UP: {
        MediaPart part = touchPart;
        if (part == null) {
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
          part.snapToPage(findSnapTarget(part, velocityX));
          return true;
        }
        return false;
      }
      case MotionEvent.ACTION_CANCEL: {
        MediaPart part = touchPart;
        if (part == null) {
          return false;
        }
        boolean wasDragging = pagerDragging;
        dropPagerTouch();
        if (wasDragging) {
          part.snapToPage(part.currentPageIndex());
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
    for (Part part : parts) {
      if (part instanceof ButtonRowPart && ((ButtonRowPart) part).keyboard.onTouchEvent(view, e)) {
        return true;
      }
    }
    if (super.onTouchEvent(view, e)) {
      return true;
    }
    for (Part part : parts) {
      if (part instanceof TextPart && ((TextPart) part).wrapper.onTouchEvent(view, e)) {
        return true;
      }
    }
    return clickHelper.onTouchEvent(view, e);
  }

  // Taps: a media segment opens the viewer at the tapped item, a text
  // segment opens the full post in the Instant View engine; button rows
  // handle their own touches through the inline keyboard

  @Override
  public boolean needClickAt (View view, float x, float y) {
    Part part = findPartAt(x, y);
    return part != null && !(part instanceof ButtonRowPart);
  }

  @Override
  public void onClickAt (View view, float x, float y) {
    Part part = findPartAt(x, y);
    if (part instanceof MediaPart || part instanceof MosaicPart) {
      MediaWrapper wrapper = findTappedWrapper(part, x, y);
      if (wrapper != null) {
        onClick(view, wrapper);
      }
    } else if (part instanceof TextPart) {
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
    Part part = findPartAt(x, y);
    if (part instanceof MediaPart || part instanceof MosaicPart) {
      MediaWrapper wrapper = findTappedWrapper(part, x, y);
      if (wrapper != null) {
        result = wrapper.performLongPress(view) || result;
      }
    } else if (part instanceof ButtonRowPart) {
      result = ((ButtonRowPart) part).keyboard.performLongPress(view) || result;
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
}
