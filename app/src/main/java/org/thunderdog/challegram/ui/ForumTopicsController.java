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
 * File created on 09/08/2026
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.AvatarPlaceholder;
import org.thunderdog.challegram.data.ContentPreview;
import org.thunderdog.challegram.telegram.ForumTopicInfoListener;
import org.thunderdog.challegram.telegram.MessageListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccentColor;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.FormattedText;
import org.thunderdog.challegram.util.text.Letters;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.BetterChatView;
import org.thunderdog.challegram.widget.ListInfoView;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class ForumTopicsController extends RecyclerViewController<ForumTopicsController.Args> implements View.OnClickListener, MessageListener, ForumTopicInfoListener {
  public static class Args {
    public final long chatId;

    public Args (long chatId) {
      this.chatId = chatId;
    }
  }

  public ForumTopicsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_forumTopics;
  }

  @Override
  public CharSequence getName () {
    return tdlib.chatTitle(getArgumentsStrict().chatId);
  }

  private SettingsAdapter adapter;
  private final ArrayList<TdApi.ForumTopic> topics = new ArrayList<>();
  private boolean initialLoadFinished;
  private boolean isLoading;
  private boolean endReached;
  private int nextOffsetDate;
  private long nextOffsetMessageId;
  private int nextOffsetForumTopicId;

  private long chatId () {
    return getArgumentsStrict().chatId;
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void setInfo (ListItem item, int position, ListInfoView infoView) {
        if (endReached) {
          infoView.showInfo(Lang.pluralBold(R.string.xTopics, topics.size()));
        } else {
          infoView.showProgress();
        }
      }

      @Override
      protected void setChatData (ListItem item, int position, BetterChatView chatView) {
        setTopicData(chatView, (TdApi.ForumTopic) item.getData());
      }
    };
    buildCells();
    recyclerView.setAdapter(adapter);
    recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrolled (@NonNull RecyclerView recyclerView, int dx, int dy) {
        if (initialLoadFinished && ((LinearLayoutManager) recyclerView.getLayoutManager()).findLastVisibleItemPosition() >= adapter.getItems().size() - 5) {
          loadMore();
        }
      }
    });
    tdlib.listeners().subscribeToMessageUpdates(chatId(), this);
    loadMore();
  }

  private void setTopicData (BetterChatView chatView, TdApi.ForumTopic topic) {
    FormattedText title;
    if (topic.info.icon != null && topic.info.icon.customEmojiId != 0) {
      title = FormattedText.concat(" ",
        FormattedText.customEmoji(tdlib, "💬", topic.info.icon.customEmojiId),
        new FormattedText(topic.info.name)
      );
    } else {
      title = new FormattedText(topic.info.name);
    }
    chatView.setTitle(title, null);
    String name = topic.info.name;
    String letter = name.isEmpty() ? "#" : name.substring(0, name.offsetByCodePoints(0, 1));
    chatView.setAvatar(null, new AvatarPlaceholder.Metadata(new TdlibAccentColor(TdlibAccentColor.InternalId.INACTIVE), new Letters(letter)));
    TdApi.Message lastMessage = topic.lastMessage;
    if (lastMessage != null) {
      ContentPreview preview = ContentPreview.getChatListPreview(tdlib, chatId(), lastMessage, false);
      chatView.setSubtitle(preview.buildFormattedText(true), null);
      chatView.setTime(Lang.timeOrDateShort(lastMessage.date, TimeUnit.SECONDS));
    } else {
      chatView.setSubtitle((CharSequence) null);
      chatView.setTime(null);
    }
    boolean muted = topic.notificationSettings != null && !topic.notificationSettings.useDefaultMuteFor && topic.notificationSettings.muteFor > 0;
    chatView.setUnreadCount(topic.unreadCount, muted, false);
  }

  @Override
  public boolean needAsynchronousAnimation () {
    return !initialLoadFinished;
  }

  private void buildCells () {
    ArrayList<ListItem> items = new ArrayList<>();
    if (!initialLoadFinished) {
      items.add(new ListItem(ListItem.TYPE_PROGRESS));
    } else if (topics.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_EMPTY, 0, 0, R.string.NothingFound));
    } else {
      items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      boolean first = true;
      for (TdApi.ForumTopic topic : topics) {
        if (first) {
          first = false;
        } else {
          items.add(new ListItem(ListItem.TYPE_SEPARATOR));
        }
        items.add(new ListItem(ListItem.TYPE_CHAT_BETTER, R.id.btn_forumTopic).setData(topic).setLongId(topic.info.forumTopicId));
      }
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
      items.add(new ListItem(ListItem.TYPE_LIST_INFO_VIEW));
    }
    adapter.setItems(items, false);
  }

  private void loadMore () {
    if (isLoading || endReached || isDestroyed()) {
      return;
    }
    isLoading = true;
    int limit = initialLoadFinished ? 40 : Screen.calculateLoadingItems(Screen.dp(72f), 20);
    tdlib.client().send(new TdApi.GetForumTopics(chatId(), null, nextOffsetDate, nextOffsetMessageId, nextOffsetForumTopicId, limit), result -> runOnUiThreadOptional(() -> {
      isLoading = false;
      boolean wasInitial = !initialLoadFinished;
      initialLoadFinished = true;
      if (result.getConstructor() == TdApi.ForumTopics.CONSTRUCTOR) {
        TdApi.ForumTopics forumTopics = (TdApi.ForumTopics) result;
        if (forumTopics.topics.length == 0) {
          endReached = true;
        } else {
          nextOffsetDate = forumTopics.nextOffsetDate;
          nextOffsetMessageId = forumTopics.nextOffsetMessageId;
          nextOffsetForumTopicId = forumTopics.nextOffsetForumTopicId;
          for (TdApi.ForumTopic topic : forumTopics.topics) {
            if (indexOfTopic(topic.info.forumTopicId) == -1) {
              topics.add(topic);
              tdlib.listeners().subscribeToForumTopicUpdates(chatId(), topic.info.forumTopicId, this);
            }
          }
        }
      } else {
        endReached = true;
      }
      buildCells();
      if (wasInitial) {
        executeScheduledAnimation();
      }
    }));
  }

  private int indexOfTopic (int forumTopicId) {
    for (int i = 0; i < topics.size(); i++) {
      if (topics.get(i).info.forumTopicId == forumTopicId) {
        return i;
      }
    }
    return -1;
  }

  private void updateTopicRow (int index, TdApi.ForumTopic topic) {
    // items: [offset, shadowTop, row0, sep, row1, sep, ...]
    int position = 2 + index * 2;
    if (position < adapter.getItems().size()) {
      adapter.getItems().get(position).setData(topic);
      adapter.updateValuedSettingByPosition(position);
    }
  }

  private void refetchTopic (int forumTopicId) {
    tdlib.client().send(new TdApi.GetForumTopic(chatId(), forumTopicId), result -> runOnUiThreadOptional(() -> {
      if (result.getConstructor() != TdApi.ForumTopic.CONSTRUCTOR) {
        return;
      }
      TdApi.ForumTopic topic = (TdApi.ForumTopic) result;
      int index = indexOfTopic(topic.info.forumTopicId);
      if (index != -1) {
        topics.set(index, topic);
        updateTopicRow(index, topic);
      }
    }));
  }

  private void onChatMessagesChanged (int forumTopicId) {
    if (forumTopicId != 0 && indexOfTopic(forumTopicId) != -1) {
      refetchTopic(forumTopicId);
    }
  }

  private static int forumTopicIdOf (TdApi.Message message) {
    return message != null && message.topicId != null && message.topicId.getConstructor() == TdApi.MessageTopicForum.CONSTRUCTOR ?
      ((TdApi.MessageTopicForum) message.topicId).forumTopicId : 0;
  }

  @Override
  public void onNewMessage (TdApi.Message message) {
    if (message.chatId == chatId()) {
      int forumTopicId = forumTopicIdOf(message);
      tdlib.ui().post(() -> {
        if (!isDestroyed()) {
          onChatMessagesChanged(forumTopicId);
        }
      });
    }
  }

  @Override
  public void onMessagesDeleted (long chatId, long[] messageIds) {
    if (chatId == chatId()) {
      tdlib.ui().post(() -> {
        if (!isDestroyed() && !topics.isEmpty()) {
          // Which topic lost messages is unknown here - refresh the visible ones lazily
          for (TdApi.ForumTopic topic : topics) {
            for (long messageId : messageIds) {
              if (topic.lastMessage != null && topic.lastMessage.id == messageId) {
                refetchTopic(topic.info.forumTopicId);
                break;
              }
            }
          }
        }
      });
    }
  }

  @Override
  public void onForumTopicInfoChanged (TdApi.ForumTopicInfo info) {
    tdlib.ui().post(() -> {
      if (!isDestroyed()) {
        int index = indexOfTopic(info.forumTopicId);
        if (index != -1) {
          topics.get(index).info = info;
          updateTopicRow(index, topics.get(index));
        }
      }
    });
  }

  @Override
  public void onForumTopicUpdated (long chatId, long forumTopicId, boolean isPinned, long lastReadInboxMessageId, long lastReadOutboxMessageId, TdApi.ChatNotificationSettings notificationSettings) {
    tdlib.ui().post(() -> {
      if (!isDestroyed()) {
        onChatMessagesChanged((int) forumTopicId);
      }
    });
  }

  @Override
  public void onClick (View v) {
    ListItem item = (ListItem) v.getTag();
    if (item != null && item.getId() == R.id.btn_forumTopic) {
      TdApi.ForumTopic topic = (TdApi.ForumTopic) item.getData();
      tdlib.ui().openChat(this, chatId(), new TdlibUi.ChatOpenParameters()
        .keepStack()
        .messageTopic(new TdApi.MessageTopicForum(topic.info.forumTopicId)));
    }
  }

  @Override
  public void destroy () {
    super.destroy();
    tdlib.listeners().unsubscribeFromMessageUpdates(chatId(), this);
    for (TdApi.ForumTopic topic : topics) {
      tdlib.listeners().unsubscribeFromForumTopicUpdates(chatId(), topic.info.forumTopicId, this);
    }
  }
}
