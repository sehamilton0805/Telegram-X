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
 * File created on 11/08/2026
 */
package org.thunderdog.challegram.data;

import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.mediaview.MediaViewController;
import org.thunderdog.challegram.mediaview.data.MediaItem;
import org.thunderdog.challegram.mediaview.data.MediaStack;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;

import tgx.td.Td;

// A story forwarded or mentioned in a chat. Opens through the standard fullscreen
// media viewer (MODE_SIMPLE): photo/video + entity-rendered caption
public class TGMessageStory extends TGMessageGiveawayBase {
  private final TdApi.MessageStory story;

  public TGMessageStory (MessagesManager manager, TdApi.Message msg, @NonNull TdApi.MessageStory story) {
    super(manager, msg);
    this.story = story;
  }

  @Override
  protected int onBuildContent (int maxWidth) {
    content = new Content(maxWidth - Screen.dp(CONTENT_PADDING_DP * 2));

    content.padding(Screen.dp(25));
    content.add(new ContentDrawable(R.drawable.baseline_camera_alt_24));
    content.padding(Screen.dp(25));

    content.add(Lang.boldify(Lang.getString(R.string.Story)), getTextColorSet(), currentViews);
    content.padding(Screen.dp(6));
    content.add(new ContentBubbles(this, maxWidth - Screen.dp(CONTENT_PADDING_DP * 2 + 60))
      .setOnClickListener(this::onBubbleClick)
      .addChatId(story.storyPosterChatId));
    if (story.viaMention) {
      content.padding(Screen.dp(6));
      content.add(Lang.getString(R.string.StoryViaMention), getTextColorSet(), currentViews);
    }

    invalidateGiveawayReceiver();
    return content.getHeight();
  }

  private void onBubbleClick (TdApi.MessageSender senderId) {
    tdlib.ui().openChat(controller(), Td.getSenderId(senderId), new TdlibUi.ChatOpenParameters().keepStack().removeDuplicates().openProfileInCaseOfPrivateChat());
  }

  @Override
  protected String getButtonText () {
    return Lang.getString(R.string.Open);
  }

  private boolean loading;

  @Override
  public void onClick (View view, TGInlineKeyboard keyboard, TGInlineKeyboard.Button button) {
    if (loading) {
      return;
    }
    loading = true;
    tdlib.client().send(new TdApi.GetStory(story.storyPosterChatId, story.storyId, false), result -> runOnUiThreadOptional(() -> {
      loading = false;
      if (result.getConstructor() != TdApi.Story.CONSTRUCTOR) {
        context().tooltipManager().builder(view).show(tdlib, Lang.getString(R.string.StoryExpired)).hideDelayed();
        return;
      }
      openStory((TdApi.Story) result);
    }));
  }

  private void openStory (TdApi.Story storyObject) {
    MediaItem item;
    switch (storyObject.content.getConstructor()) {
      case TdApi.StoryContentPhoto.CONSTRUCTOR: {
        TdApi.StoryContentPhoto photo = (TdApi.StoryContentPhoto) storyObject.content;
        item = MediaItem.valueOf(context(), tdlib, photo.photo, storyObject.caption);
        break;
      }
      case TdApi.StoryContentVideo.CONSTRUCTOR: {
        TdApi.StoryVideo storyVideo = ((TdApi.StoryContentVideo) storyObject.content).video;
        // Stories carry StoryVideo, the viewer wants TdApi.Video - adapt in place.
        // MPEG4 thumbnails are not consumable by the photo pipeline - drop them
        TdApi.Thumbnail thumbnail = storyVideo.thumbnail != null && storyVideo.thumbnail.format.getConstructor() == TdApi.ThumbnailFormatJpeg.CONSTRUCTOR ? storyVideo.thumbnail : null;
        TdApi.Video video = new TdApi.Video((int) storyVideo.duration, storyVideo.width, storyVideo.height, "story.mp4", "video/mp4", storyVideo.hasStickers, true, storyVideo.minithumbnail, thumbnail, storyVideo.video);
        item = MediaItem.valueOf(context(), tdlib, video, null, null, storyObject.caption);
        break;
      }
      default: {
        UI.showToast(R.string.StoryUnsupported, Toast.LENGTH_SHORT);
        return;
      }
    }
    if (item == null) {
      UI.showToast(R.string.StoryUnsupported, Toast.LENGTH_SHORT);
      return;
    }
    // Marks the story as viewed; the viewer popup has no close callback we own
    tdlib.client().send(new TdApi.OpenStory(story.storyPosterChatId, story.storyId), ignored ->
      tdlib.client().send(new TdApi.CloseStory(story.storyPosterChatId, story.storyId), tdlib.okHandler()));
    MediaStack stack = new MediaStack(context(), tdlib);
    stack.set(item);
    MediaViewController.openWithStack(controller(), stack, tdlib.chatTitle(story.storyPosterChatId), null, false);
  }

  @Override
  public boolean onLongClick (View view, TGInlineKeyboard keyboard, TGInlineKeyboard.Button button) {
    return false;
  }
}
