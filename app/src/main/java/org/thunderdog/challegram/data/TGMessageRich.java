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
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.mediaview.MediaViewController;
import org.thunderdog.challegram.mediaview.data.MediaItem;
import org.thunderdog.challegram.mediaview.data.MediaStack;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.TextWrapper;

import java.util.ArrayList;
import java.util.List;

// Rich messages (PageBlock-based posts): all media blocks rendered as an
// album-style mosaic, the flattened block text below. The full per-block
// renderer (tables, embeds, a real carousel) remains a follow-up
public class TGMessageRich extends TGMessage implements MediaWrapper.OnClickListener {
  private final TdApi.RichMessage richMessage;
  private final ArrayList<TdApi.PageBlock> mediaBlocks;
  private final ArrayList<MediaWrapper> wrappers = new ArrayList<>();
  private MosaicWrapper mosaicWrapper;
  private TextWrapper text;

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
    if (mosaicWrapper == null) {
      for (TdApi.PageBlock block : mediaBlocks) {
        MediaWrapper wrapper = newMediaWrapper(block);
        if (wrapper == null) {
          continue;
        }
        wrapper.setViewProvider(currentViews);
        wrapper.setOnClickListener(this);
        wrappers.add(wrapper);
        if (mosaicWrapper == null) {
          mosaicWrapper = new MosaicWrapper(wrapper, this);
        } else {
          mosaicWrapper.addItem(wrapper, true);
        }
      }
      TdApi.FormattedText flatText = TD.textFromRichMessage(richMessage, false);
      if (!flatText.text.isEmpty()) {
        this.text = new TextWrapper(tdlib, flatText, getTextStyleProvider(), getTextColorSet(), openParameters(), null)
          .setViewProvider(currentViews);
      }
    }
    if (mosaicWrapper != null) {
      int maxHeight = Math.max(Screen.dp(120f), (int) (maxWidth * 1.2f));
      // MIN_LAYOUT_* are dp values - convert to px and clamp, mirroring TGMessageMedia
      int minWidth = Math.min(Screen.dp(MosaicWrapper.MIN_LAYOUT_WIDTH), maxWidth);
      int minHeight = Math.min(Screen.dp(MosaicWrapper.MIN_LAYOUT_HEIGHT), maxHeight);
      mosaicWrapper.build(maxWidth, maxHeight, minWidth, minHeight, MosaicWrapper.MODE_FIT_WIDTH, false);
    }
    if (text != null) {
      text.prepare(maxWidth);
    }
  }

  @Override
  protected int getContentWidth () {
    int width = 0;
    if (mosaicWrapper != null) {
      width = mosaicWrapper.getWidth();
    }
    if (text != null) {
      width = Math.max(width, text.getWidth());
    }
    return width;
  }

  @Override
  protected int getContentHeight () {
    int height = 0;
    if (mosaicWrapper != null) {
      height += mosaicWrapper.getHeight();
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
    if (mosaicWrapper != null) {
      mosaicWrapper.requestFiles(receiver, invalidate);
    } else {
      receiver.clear();
    }
  }

  @Override
  protected void drawContent (MessageView view, Canvas c, int startX, int startY, int maxWidth, ComplexReceiver receiver) {
    int y = startY;
    if (mosaicWrapper != null) {
      mosaicWrapper.draw(view, c, startX, y, receiver, false);
      y += mosaicWrapper.getHeight() + Screen.dp(10f);
    }
    if (text != null) {
      text.draw(c, startX, startX + maxWidth, 0, y, null, 1f, view.getTextMediaReceiver());
    }
  }

  @Override
  public boolean onTouchEvent (MessageView view, MotionEvent e) {
    if (super.onTouchEvent(view, e)) {
      return true;
    }
    if (text != null && text.onTouchEvent(view, e)) {
      return true;
    }
    return mosaicWrapper != null && mosaicWrapper.onTouchEvent(view, e);
  }

  @Override
  public boolean performLongPress (View view, float x, float y) {
    boolean result = super.performLongPress(view, x, y);
    if (mosaicWrapper != null) {
      result = mosaicWrapper.performLongPress(view) || result;
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
    if (mosaicWrapper != null) {
      mosaicWrapper.autoDownloadContent(type);
    }
  }

  @Override
  protected void onMessageContainerDestroyed () {
    if (mosaicWrapper != null) {
      mosaicWrapper.destroy();
    }
  }
}
