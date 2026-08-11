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
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TGMessageStory;
import org.thunderdog.challegram.mediaview.MediaViewController;
import org.thunderdog.challegram.mediaview.data.MediaItem;
import org.thunderdog.challegram.mediaview.data.MediaStack;
import org.thunderdog.challegram.telegram.SortedList;
import org.thunderdog.challegram.telegram.StoryList;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.BetterChatView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

// Active stories feed: chats with active stories from the main story list.
// Tapping a chat opens its whole story chain in the media viewer
public class StoriesFeedController extends RecyclerViewController<Void> implements View.OnClickListener {
  public StoriesFeedController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_storiesFeed;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.StoriesFeed);
  }

  private SettingsAdapter adapter;
  private StoryList storyList;
  private final ArrayList<TdApi.ChatActiveStories> items = new ArrayList<>();
  private boolean initialLoadFinished;
  // SortedList holds listeners weakly - this strong reference keeps it alive
  private final SortedList.ListListener<TdApi.ChatActiveStories> listListener = new SortedList.ListListener<TdApi.ChatActiveStories>() {
    @Override
    public void onListChanged (SortedList<TdApi.ChatActiveStories> list) {
      list.getList(null, copy -> runOnUiThreadOptional(() -> {
        items.clear();
        items.addAll(copy);
        initialLoadFinished = true;
        buildCells();
      }));
    }
  };

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void setChatData (ListItem item, int position, BetterChatView chatView) {
        bindChat(chatView, (TdApi.ChatActiveStories) item.getData());
      }
    };
    buildCells();
    recyclerView.setAdapter(adapter);
    recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrolled (@NonNull RecyclerView recyclerView, int dx, int dy) {
        if (dy != 0 && initialLoadFinished && storyList != null && storyList.canLoad() &&
          ((LinearLayoutManager) recyclerView.getLayoutManager()).findLastVisibleItemPosition() >= adapter.getItems().size() - 8) {
          storyList.loadMore(20, null);
        }
      }
    });
    storyList = tdlib.getStoryList(new TdApi.StoryListMain());
    storyList.initializeList(null, listListener, copy -> runOnUiThreadOptional(() -> {
      items.clear();
      items.addAll(copy);
      initialLoadFinished = true;
      buildCells();
      executeScheduledAnimation();
    }), 20, null);
  }

  private void bindChat (BetterChatView chatView, TdApi.ChatActiveStories activeStories) {
    chatView.setChatAvatar(activeStories.chatId);
    chatView.setTitle(tdlib.chatTitle(activeStories.chatId));
    chatView.setSubtitle(Lang.getString(R.string.StoriesCount, activeStories.stories.length));
    if (activeStories.stories.length > 0) {
      chatView.setTime(Lang.timeOrDateShort(activeStories.stories[activeStories.stories.length - 1].date, TimeUnit.SECONDS));
      int unread = 0;
      for (TdApi.StoryInfo info : activeStories.stories) {
        if (info.storyId > activeStories.maxReadStoryId) {
          unread++;
        }
      }
      chatView.setUnreadCount(unread, false, false);
    } else {
      chatView.setTime(null);
      chatView.setUnreadCount(0, false, false);
    }
  }

  private void buildCells () {
    ArrayList<ListItem> items = new ArrayList<>();
    if (!initialLoadFinished) {
      items.add(new ListItem(ListItem.TYPE_PROGRESS));
    } else if (this.items.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_EMPTY, 0, 0, R.string.StoriesFeedEmpty));
    } else {
      items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      boolean first = true;
      for (TdApi.ChatActiveStories activeStories : this.items) {
        if (first) {
          first = false;
        } else {
          items.add(new ListItem(ListItem.TYPE_SEPARATOR));
        }
        items.add(new ListItem(ListItem.TYPE_CHAT_BETTER, R.id.btn_storyFeedChat).setData(activeStories).setLongId(activeStories.chatId));
      }
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    }
    adapter.setItems(items, false);
  }

  @Override
  public boolean needAsynchronousAnimation () {
    return !initialLoadFinished;
  }

  @Override
  public void onClick (View v) {
    ListItem item = (ListItem) v.getTag();
    if (item == null || item.getId() != R.id.btn_storyFeedChat) {
      return;
    }
    TdApi.ChatActiveStories activeStories = (TdApi.ChatActiveStories) item.getData();
    if (activeStories.stories.length == 0) {
      return;
    }
    openStoryChain(activeStories);
  }

  private void openStoryChain (TdApi.ChatActiveStories activeStories) {
    openStoryChain(this, tdlib, activeStories);
  }

  private static boolean opening;

  // Also used by the chat list story rings
  public static void openStoryChain (org.thunderdog.challegram.navigation.ViewController<?> context, Tdlib tdlib, TdApi.ChatActiveStories activeStories) {
    if (opening || activeStories.stories.length == 0) {
      return;
    }
    opening = true;
    final long chatId = activeStories.chatId;
    final int count = activeStories.stories.length;
    final TdApi.Story[] stories = new TdApi.Story[count];
    final int[] remaining = {count};
    for (int i = 0; i < count; i++) {
      final int index = i;
      tdlib.client().send(new TdApi.GetStory(chatId, activeStories.stories[i].storyId, false), result -> {
        if (result.getConstructor() == TdApi.Story.CONSTRUCTOR) {
          stories[index] = (TdApi.Story) result;
        }
        // TDLib result handlers run sequentially on one thread
        if (--remaining[0] == 0) {
          tdlib.ui().post(() -> showStoryChain(context, tdlib, chatId, stories));
        }
      });
    }
  }

  private static void showStoryChain (org.thunderdog.challegram.navigation.ViewController<?> context, Tdlib tdlib, long chatId, TdApi.Story[] stories) {
    opening = false;
    if (context.isDestroyed()) {
      return;
    }
    List<MediaItem> mediaItems = new ArrayList<>(stories.length);
    for (TdApi.Story story : stories) {
      if (story == null) {
        continue;
      }
      MediaItem item = TGMessageStory.toMediaItem(context.context(), tdlib, story);
      if (item != null) {
        mediaItems.add(item);
        // Mark as viewed: the viewer popup exposes no close/page callbacks we own
        tdlib.client().send(new TdApi.OpenStory(chatId, story.id), ignored ->
          tdlib.client().send(new TdApi.CloseStory(chatId, story.id), tdlib.okHandler()));
      }
    }
    if (mediaItems.isEmpty()) {
      UI.showToast(R.string.StoryUnsupported, Toast.LENGTH_SHORT);
      return;
    }
    MediaStack stack = new MediaStack(context.context(), tdlib);
    stack.set(0, mediaItems);
    MediaViewController.openWithStack(context, stack, tdlib.chatTitle(chatId), null, false);
  }
}
