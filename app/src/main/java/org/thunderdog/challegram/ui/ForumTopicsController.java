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
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.AvatarPlaceholder;
import org.thunderdog.challegram.data.ContentPreview;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.loader.AvatarReceiver;
import org.thunderdog.challegram.telegram.ChatListListener;
import org.thunderdog.challegram.telegram.ForumTopicInfoListener;
import org.thunderdog.challegram.telegram.MessageListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccentColor;
import org.thunderdog.challegram.telegram.TdlibChatList;
import org.thunderdog.challegram.telegram.TdlibChatListSlice;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.StringList;
import org.thunderdog.challegram.util.text.Counter;
import org.thunderdog.challegram.util.text.FormattedText;
import org.thunderdog.challegram.util.text.Letters;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.AttachDelegate;
import org.thunderdog.challegram.widget.BetterChatView;
import org.thunderdog.challegram.widget.ListInfoView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.collection.IntList;
import me.vkryl.core.lambda.Destroyable;
import tgx.td.ChatPosition;

public class ForumTopicsController extends RecyclerViewController<ForumTopicsController.Args> implements View.OnClickListener, View.OnLongClickListener, MessageListener, ForumTopicInfoListener, ChatListListener {
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
  // Bumped on every in-place forum swap: async responses captured under an older
  // generation must be dropped, they belong to the previous chat
  private int topicsGeneration;

  private static final int RAIL_WIDTH_DP = 64;
  private RecyclerView railRecyclerView;
  private RailAdapter railAdapter;
  private TdlibChatListSlice chatListSlice;

  private long chatId () {
    return getArgumentsStrict().chatId;
  }

  @Override
  protected View onCreateView (Context context) {
    View view = super.onCreateView(context);
    FrameLayoutFix wrap = (FrameLayoutFix) view;
    railAdapter = new RailAdapter();
    railRecyclerView = new RecyclerView(context);
    railRecyclerView.setLayoutManager(new LinearLayoutManager(context, RecyclerView.VERTICAL, false));
    railRecyclerView.setVerticalScrollBarEnabled(false);
    railRecyclerView.setAdapter(railAdapter);
    railRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrolled (@NonNull RecyclerView recyclerView, int dx, int dy) {
        if (dy != 0 && chatListSlice != null && chatListSlice.canLoad() &&
          ((LinearLayoutManager) recyclerView.getLayoutManager()).findLastVisibleItemPosition() >= railAdapter.getItemCount() - 8) {
          chatListSlice.loadMore(20, null);
        }
      }
    });
    wrap.addView(railRecyclerView, FrameLayoutFix.newParams(Screen.dp(RAIL_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT));
    Views.setLeftMargin(getRecyclerView(), Screen.dp(RAIL_WIDTH_DP));
    chatListSlice = tdlib.chatList(ChatPosition.CHAT_LIST_MAIN).slice(null, true, null);
    chatListSlice.initializeList(this, this::displayRailChats, 30, () -> {});
    return view;
  }

  @Override
  protected void onBottomInsetChanged (int extraBottomInset, int extraBottomInsetWithoutIme, boolean isImeInset) {
    super.onBottomInsetChanged(extraBottomInset, extraBottomInsetWithoutIme, isImeInset);
    Views.applyBottomInset(railRecyclerView, extraBottomInset);
  }

  // The slice callback delivers DELTAS: each invocation carries only the entries past the
  // previously displayed count (initial chunk, then every loadMore/backfill) - append, never replace
  private void displayRailChats (List<TdlibChatListSlice.Entry> entries) {
    final ArrayList<TdApi.Chat> chats = new ArrayList<>(entries.size());
    for (TdlibChatList.Entry entry : entries) {
      chats.add(entry.chat);
    }
    runOnUiThreadOptional(() -> {
      boolean isInitialChunk = railAdapter.getItemCount() == 0;
      railAdapter.addChats(chats);
      if (isInitialChunk) {
        int index = railAdapter.indexOfChat(chatId());
        if (index != -1) {
          ((LinearLayoutManager) railRecyclerView.getLayoutManager()).scrollToPositionWithOffset(index, Screen.dp(RAIL_WIDTH_DP));
        }
      }
    });
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
    chatView.setTopicStyle();
    String name = topic.info.name + (topic.info.isClosed ? " 🔒" : "");
    chatView.setTitle(new FormattedText(name), null);
    long iconEmojiId = topic.info.icon != null ? topic.info.icon.customEmojiId : 0;
    chatView.setAvatarCustomEmoji(iconEmojiId, 20f);
    if (iconEmojiId == 0) {
      String letter = topic.info.name.isEmpty() ? "#" : topic.info.name.substring(0, topic.info.name.offsetByCodePoints(0, 1));
      TdlibAccentColor accentColor;
      if (topic.info.icon != null && topic.info.icon.color != 0) {
        // Tint the placeholder with the topic's own icon color, like other clients do.
        // Unique id per topic: AvatarReceiver keys placeholder identity by accent id
        int argb = 0xFF000000 | topic.info.icon.color;
        accentColor = new TdlibAccentColor(new TdApi.AccentColor(-(0x100 + topic.info.forumTopicId), 0, new int[] {argb}, new int[] {argb}, 0));
      } else {
        accentColor = new TdlibAccentColor(TdlibAccentColor.InternalId.INACTIVE);
      }
      chatView.setAvatar(null, new AvatarPlaceholder.Metadata(accentColor, new Letters(letter)));
    }
    TdApi.Message lastMessage = topic.lastMessage;
    if (lastMessage != null) {
      ContentPreview preview = ContentPreview.getChatListPreview(tdlib, chatId(), lastMessage, false);
      TdApi.FormattedText previewText = preview.buildFormattedText(true);
      String senderPrefix = tdlib.senderName(lastMessage.senderId, true) + ": ";
      TdApi.TextEntity prefixEntity = new TdApi.TextEntity(0, senderPrefix.length(), new TdApi.TextEntityTypeBold());
      TdApi.TextEntity[] entities;
      if (previewText.entities != null && previewText.entities.length > 0) {
        // Copy: previewText aliases topic.lastMessage.content, mutation would corrupt it on rebinds
        entities = new TdApi.TextEntity[previewText.entities.length + 1];
        entities[0] = prefixEntity;
        for (int i = 0; i < previewText.entities.length; i++) {
          TdApi.TextEntity entity = previewText.entities[i];
          entities[i + 1] = new TdApi.TextEntity(entity.offset + senderPrefix.length(), entity.length, entity.type);
        }
      } else {
        entities = new TdApi.TextEntity[] {prefixEntity};
      }
      chatView.setSubtitle(new TdApi.FormattedText(senderPrefix + previewText.text, entities), null);
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
      if (canCreateTopic()) {
        items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));
        items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
        items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_createTopic, R.drawable.baseline_add_24, R.string.CreateTopic).setTextColorId(ColorId.textNeutral));
        items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
      } else {
        items.add(new ListItem(ListItem.TYPE_EMPTY, 0, 0, R.string.NothingFound));
      }
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
      if (endReached && canCreateTopic()) {
        items.add(new ListItem(ListItem.TYPE_SEPARATOR));
        items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_createTopic, R.drawable.baseline_add_24, R.string.CreateTopic).setTextColorId(ColorId.textNeutral));
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
    final int generation = topicsGeneration;
    int limit = initialLoadFinished ? 40 : Screen.calculateLoadingItems(Screen.dp(72f), 20);
    tdlib.client().send(new TdApi.GetForumTopics(chatId(), null, nextOffsetDate, nextOffsetMessageId, nextOffsetForumTopicId, limit), result -> runOnUiThreadOptional(() -> {
      if (generation != topicsGeneration) {
        return; // Late response for a previously displayed forum
      }
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
      checkStuckUnreadCounter();
    }));
  }

  // TDLib's forum chat counter is known to get out of sync with per-topic counters.
  // This screen is the one place where the truth is visible: once every topic is
  // loaded and none has unread messages, a lit chat badge is provably stale - fix it
  private void checkStuckUnreadCounter () {
    if (!endReached) {
      return;
    }
    for (TdApi.ForumTopic topic : topics) {
      if (topic.unreadCount > 0) {
        return;
      }
    }
    if (unstickingUnreadCounter) {
      return;
    }
    TdApi.Chat chat = tdlib.chat(chatId());
    if (chat != null && chat.unreadCount > 0 && chat.lastMessage != null) {
      unstickingUnreadCounter = true;
      // 'after' runs on Ok (UI thread); on error the flag stays set - retrying a failing ViewMessages is pointless anyway
      tdlib.markChatAsRead(chatId(), new TdApi.MessageSourceChatList(), false, () -> unstickingUnreadCounter = false);
    }
  }

  private boolean unstickingUnreadCounter;

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
    final int generation = topicsGeneration;
    tdlib.client().send(new TdApi.GetForumTopic(chatId(), forumTopicId), result -> runOnUiThreadOptional(() -> {
      if (generation != topicsGeneration || result.getConstructor() != TdApi.ForumTopic.CONSTRUCTOR) {
        return;
      }
      TdApi.ForumTopic topic = (TdApi.ForumTopic) result;
      int index = indexOfTopic(topic.info.forumTopicId);
      if (index != -1) {
        topics.set(index, topic);
        updateTopicRow(index, topic);
      }
      checkStuckUnreadCounter();
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
    if (item == null) {
      return;
    }
    if (item.getId() == R.id.btn_forumTopic) {
      TdApi.ForumTopic topic = (TdApi.ForumTopic) item.getData();
      tdlib.ui().openChat(this, chatId(), new TdlibUi.ChatOpenParameters()
        .keepStack()
        .messageTopic(new TdApi.MessageTopicForum(topic.info.forumTopicId)));
    } else if (item.getId() == R.id.btn_createTopic) {
      showCreateTopic();
    }
  }

  @Override
  public boolean onLongClick (View v) {
    ListItem item = (ListItem) v.getTag();
    if (item != null && item.getId() == R.id.btn_forumTopic) {
      showTopicOptions((TdApi.ForumTopic) item.getData());
      return true;
    }
    return false;
  }

  // Topic management

  private boolean canManageTopics () {
    TdApi.ChatMemberStatus status = tdlib.chatStatus(chatId());
    if (status == null) {
      return false;
    }
    switch (status.getConstructor()) {
      case TdApi.ChatMemberStatusCreator.CONSTRUCTOR:
        return true;
      case TdApi.ChatMemberStatusAdministrator.CONSTRUCTOR:
        return ((TdApi.ChatMemberStatusAdministrator) status).rights.canManageTopics;
    }
    return false;
  }

  private boolean canCreateTopic () {
    if (canManageTopics()) {
      return true;
    }
    TdApi.Chat chat = tdlib.chat(chatId());
    if (chat == null || chat.permissions == null || !chat.permissions.canCreateTopics) {
      return false;
    }
    TdApi.ChatMemberStatus status = tdlib.chatStatus(chatId());
    if (status != null && status.getConstructor() == TdApi.ChatMemberStatusRestricted.CONSTRUCTOR) {
      return ((TdApi.ChatMemberStatusRestricted) status).permissions.canCreateTopics;
    }
    return true;
  }

  private void showTopicOptions (TdApi.ForumTopic topic) {
    boolean canManage = canManageTopics();
    boolean canEdit = canManage || topic.info.isOutgoing;
    IntList ids = new IntList(4);
    StringList strings = new StringList(4);
    IntList icons = new IntList(4);
    ids.append(R.id.btn_topicMute);
    strings.append(R.string.Notifications);
    icons.append(R.drawable.baseline_notifications_24);
    if (canManage) {
      ids.append(R.id.btn_topicPin);
      strings.append(topic.isPinned ? R.string.Unpin : R.string.PinToTop);
      icons.append(R.drawable.deproko_baseline_pin_24);
    }
    if (canEdit) {
      ids.append(R.id.btn_topicRename);
      strings.append(R.string.TopicRename);
      icons.append(R.drawable.baseline_edit_24);
      ids.append(R.id.btn_topicClose);
      strings.append(topic.info.isClosed ? R.string.TopicReopen : R.string.TopicClose);
      // No lock-open drawable in the project - replay arrow reads as "reopen"
      icons.append(topic.info.isClosed ? R.drawable.baseline_replay_24 : R.drawable.baseline_lock_24);
    }
    showOptions(topic.info.name, ids.get(), strings.get(), null, icons.get(), (optionItemView, id) -> {
      if (id == R.id.btn_topicMute) {
        showTopicMuteOptions(topic);
      } else if (id == R.id.btn_topicPin) {
        tdlib.client().send(new TdApi.ToggleForumTopicIsPinned(chatId(), topic.info.forumTopicId, !topic.isPinned), tdlib.okHandler(this::reloadTopics));
      } else if (id == R.id.btn_topicRename) {
        showRenameTopic(topic);
      } else if (id == R.id.btn_topicClose) {
        tdlib.client().send(new TdApi.ToggleForumTopicIsClosed(chatId(), topic.info.forumTopicId, !topic.info.isClosed), tdlib.okHandler());
      }
      return true;
    });
  }

  private int topicMuteFor (TdApi.ForumTopic topic) {
    if (topic.notificationSettings == null || topic.notificationSettings.useDefaultMuteFor) {
      return tdlib.chatMuteFor(chatId());
    }
    return topic.notificationSettings.muteFor;
  }

  private void showTopicMuteOptions (TdApi.ForumTopic topic) {
    IntList ids = new IntList(5);
    IntList icons = new IntList(5);
    StringList strings = new StringList(5);
    int muteFor = topicMuteFor(topic);
    boolean mutedForever = TD.isMutedForever(muteFor);
    TdlibUi.fillMuteOptions(ids, icons, strings, null, muteFor > 0, !mutedForever, !mutedForever, false, false, null, false);
    showOptions(topic.info.name, ids.get(), strings.get(), null, icons.get(), (optionItemView, id) -> {
      int newMuteFor = TdlibUi.getMuteDurationForId(id);
      if (newMuteFor >= 0) {
        setTopicMuteFor(topic, newMuteFor);
      }
      return true;
    });
  }

  private void setTopicMuteFor (TdApi.ForumTopic topic, int muteFor) {
    TdApi.ChatNotificationSettings settings = topic.notificationSettings;
    if (settings == null) {
      return;
    }
    // Topic-level "default" falls back to the forum chat's setting, one level down from Tdlib.setMuteFor
    settings.useDefaultMuteFor = muteFor == 0 && tdlib.chatMuteFor(chatId()) == 0;
    settings.muteFor = muteFor;
    tdlib.client().send(new TdApi.SetForumTopicNotificationSettings(chatId(), topic.info.forumTopicId, settings), tdlib.okHandler());
  }

  private void showRenameTopic (TdApi.ForumTopic topic) {
    openInputAlert(Lang.getString(R.string.TopicRename), Lang.getString(R.string.TopicName), R.string.Save, R.string.Cancel, topic.info.name, (inputView, result) -> {
      String name = result.trim();
      if (name.isEmpty() || name.length() > 128) {
        return false;
      }
      tdlib.client().send(new TdApi.EditForumTopic(chatId(), topic.info.forumTopicId, name, false, 0), tdlib.okHandler());
      return true;
    }, true);
  }

  private static final int[] TOPIC_ICON_COLORS = {0x6FB9F0, 0xFFD67E, 0xCB86DB, 0x8EEE98, 0xFF93B2, 0xFB6F5F};

  private void showCreateTopic () {
    openInputAlert(Lang.getString(R.string.CreateTopic), Lang.getString(R.string.TopicName), R.string.CreateTopic, R.string.Cancel, null, (inputView, result) -> {
      String name = result.trim();
      if (name.isEmpty() || name.length() > 128) {
        return false;
      }
      int color = TOPIC_ICON_COLORS[Math.abs(name.hashCode()) % TOPIC_ICON_COLORS.length];
      tdlib.client().send(new TdApi.CreateForumTopic(chatId(), name, false, new TdApi.ForumTopicIcon(color, 0)), createResult -> {
        if (createResult.getConstructor() == TdApi.ForumTopicInfo.CONSTRUCTOR) {
          reloadTopics();
        } else {
          tdlib.okHandler().onResult(createResult);
        }
      });
      return true;
    }, true);
  }

  // Full reload keeping subscriptions to the chat itself: used when server-side ordering
  // changes (pin/unpin) or the topic set changes in a way updates don't cover (create)
  private void reloadTopics () {
    runOnUiThreadOptional(() -> {
      for (TdApi.ForumTopic topic : topics) {
        tdlib.listeners().unsubscribeFromForumTopicUpdates(chatId(), topic.info.forumTopicId, this);
      }
      topics.clear();
      initialLoadFinished = false;
      isLoading = false;
      endReached = false;
      nextOffsetDate = 0;
      nextOffsetMessageId = 0;
      nextOffsetForumTopicId = 0;
      topicsGeneration++;
      buildCells();
      loadMore();
    });
  }

  // Chat rail

  @Override
  public void onChatAdded (TdlibChatList chatList, TdApi.Chat chat, int atIndex, Tdlib.ChatChange changeInfo) {
    runOnUiThreadOptional(() -> railAdapter.addChat(chat, atIndex));
  }

  @Override
  public void onChatRemoved (TdlibChatList chatList, TdApi.Chat chat, int fromIndex, Tdlib.ChatChange changeInfo) {
    runOnUiThreadOptional(() -> railAdapter.removeChat(fromIndex));
  }

  @Override
  public void onChatMoved (TdlibChatList chatList, TdApi.Chat chat, int fromIndex, int toIndex, Tdlib.ChatChange changeInfo) {
    runOnUiThreadOptional(() -> railAdapter.moveChat(fromIndex, toIndex));
  }

  @Override
  public void onChatChanged (TdlibChatList chatList, TdApi.Chat chat, int index, Tdlib.ChatChange changeInfo) {
    runOnUiThreadOptional(() -> railAdapter.updateChat(index));
  }

  @Override
  public void onChatListItemChanged (TdlibChatList chatList, TdApi.Chat chat, int changeType) {
    runOnUiThreadOptional(() -> {
      int index = railAdapter.indexOfChat(chat.id);
      if (index != -1) {
        railAdapter.updateChat(index);
      }
    });
  }

  private void onRailChatClick (long clickedChatId) {
    if (clickedChatId == chatId() || navigationController() == null) {
      return;
    }
    if (tdlib.isForum(clickedChatId)) {
      // openChat would push a second ForumTopicsController on top - swap in place instead
      switchToForum(clickedChatId);
    } else {
      tdlib.ui().openChat(this, clickedChatId, new TdlibUi.ChatOpenParameters().keepStack());
    }
  }

  private void switchToForum (long newChatId) {
    long oldChatId = chatId();
    tdlib.listeners().unsubscribeFromMessageUpdates(oldChatId, this);
    for (TdApi.ForumTopic topic : topics) {
      tdlib.listeners().unsubscribeFromForumTopicUpdates(oldChatId, topic.info.forumTopicId, this);
    }
    setArguments(new Args(newChatId));
    topics.clear();
    initialLoadFinished = false;
    isLoading = false;
    endReached = false;
    nextOffsetDate = 0;
    nextOffsetMessageId = 0;
    nextOffsetForumTopicId = 0;
    topicsGeneration++;
    unstickingUnreadCounter = false;
    tdlib.listeners().subscribeToMessageUpdates(newChatId, this);
    buildCells();
    ((LinearLayoutManager) getRecyclerView().getLayoutManager()).scrollToPositionWithOffset(0, 0);
    loadMore();
    int oldIndex = railAdapter.indexOfChat(oldChatId);
    if (oldIndex != -1) {
      railAdapter.updateChat(oldIndex);
    }
    int newIndex = railAdapter.indexOfChat(newChatId);
    if (newIndex != -1) {
      railAdapter.updateChat(newIndex);
    }
    setName(getName());
  }

  @Override
  public boolean passNameToHeader () {
    return true;
  }

  @Override
  public void destroy () {
    super.destroy();
    tdlib.listeners().unsubscribeFromMessageUpdates(chatId(), this);
    for (TdApi.ForumTopic topic : topics) {
      tdlib.listeners().unsubscribeFromForumTopicUpdates(chatId(), topic.info.forumTopicId, this);
    }
    if (chatListSlice != null) {
      chatListSlice.performDestroy();
    }
    if (railRecyclerView != null) {
      Views.destroyRecyclerView(railRecyclerView);
    }
  }

  private class RailAdapter extends RecyclerView.Adapter<RailHolder> {
    private final ArrayList<TdApi.Chat> chats = new ArrayList<>();

    void addChats (List<TdApi.Chat> newChats) {
      int startIndex = chats.size();
      chats.addAll(newChats);
      notifyItemRangeInserted(startIndex, newChats.size());
    }

    void addChat (TdApi.Chat chat, int atIndex) {
      if (atIndex >= 0 && atIndex <= chats.size()) {
        chats.add(atIndex, chat);
        notifyItemInserted(atIndex);
      }
    }

    void removeChat (int fromIndex) {
      if (fromIndex >= 0 && fromIndex < chats.size()) {
        chats.remove(fromIndex);
        notifyItemRemoved(fromIndex);
      }
    }

    void moveChat (int fromIndex, int toIndex) {
      if (fromIndex >= 0 && fromIndex < chats.size() && toIndex >= 0 && toIndex < chats.size()) {
        chats.add(toIndex, chats.remove(fromIndex));
        notifyItemMoved(fromIndex, toIndex);
      }
    }

    void updateChat (int index) {
      if (index >= 0 && index < chats.size()) {
        notifyItemChanged(index);
      }
    }

    int indexOfChat (long chatId) {
      for (int i = 0; i < chats.size(); i++) {
        if (chats.get(i).id == chatId) {
          return i;
        }
      }
      return -1;
    }

    @NonNull
    @Override
    public RailHolder onCreateViewHolder (@NonNull ViewGroup parent, int viewType) {
      ChatRailItemView view = new ChatRailItemView(parent.getContext(), tdlib);
      view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(RAIL_WIDTH_DP)));
      view.setOnClickListener(v -> onRailChatClick(((ChatRailItemView) v).getChatId()));
      addThemeInvalidateListener(view);
      return new RailHolder(view);
    }

    @Override
    public void onBindViewHolder (@NonNull RailHolder holder, int position) {
      TdApi.Chat chat = chats.get(position);
      ((ChatRailItemView) holder.itemView).setChat(chat, chat.id == chatId());
    }

    @Override
    public void onViewAttachedToWindow (@NonNull RailHolder holder) {
      ((ChatRailItemView) holder.itemView).attach();
    }

    @Override
    public void onViewDetachedFromWindow (@NonNull RailHolder holder) {
      ((ChatRailItemView) holder.itemView).detach();
    }

    @Override
    public int getItemCount () {
      return chats.size();
    }
  }

  private static class RailHolder extends RecyclerView.ViewHolder {
    RailHolder (View itemView) {
      super(itemView);
    }
  }

  private static class ChatRailItemView extends View implements AttachDelegate, Destroyable {
    private final Tdlib tdlib;
    private final AvatarReceiver avatarReceiver;
    private final Counter counter;
    private final RectF selectionRect = new RectF();
    private long chatId;
    private boolean isSelected;

    ChatRailItemView (Context context, Tdlib tdlib) {
      super(context);
      this.tdlib = tdlib;
      this.avatarReceiver = new AvatarReceiver(this);
      this.counter = new Counter.Builder().callback(this).outlineColor(ColorId.filling).build();
    }

    long getChatId () {
      return chatId;
    }

    void setChat (TdApi.Chat chat, boolean isSelected) {
      this.chatId = chat.id;
      this.isSelected = isSelected;
      avatarReceiver.requestChat(tdlib, chat.id, AvatarReceiver.Options.NONE);
      int unreadCount = chat.unreadCount > 0 ? chat.unreadCount : chat.isMarkedAsUnread ? Tdlib.CHAT_MARKED_AS_UNREAD : 0;
      counter.setCount(unreadCount, !tdlib.chatNotificationsEnabled(chat), false);
      invalidate();
    }

    @Override
    protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
      setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), Screen.dp(RAIL_WIDTH_DP));
    }

    @Override
    protected void onDraw (Canvas c) {
      if (isSelected) {
        selectionRect.set(Screen.dp(5f), Screen.dp(5f), getWidth() - Screen.dp(5f), getHeight() - Screen.dp(5f));
        c.drawRoundRect(selectionRect, Screen.dp(16f), Screen.dp(16f), Paints.fillingPaint(Theme.getColor(ColorId.fillingPressed)));
      }
      int radius = Screen.dp(24f);
      int centerX = getWidth() / 2;
      int centerY = getHeight() / 2;
      avatarReceiver.setBounds(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
      if (avatarReceiver.needPlaceholder()) {
        avatarReceiver.drawPlaceholder(c);
      }
      avatarReceiver.draw(c);
      // Badge on the top-right edge of the avatar circle, like VerticalChatView
      float displayRadius = avatarReceiver.getDisplayRadius();
      double topRightRadians = Math.toRadians(135f);
      float badgeCenterX = avatarReceiver.getRight() - displayRadius;
      float badgeCenterY = avatarReceiver.getTop() + displayRadius;
      float x = badgeCenterX + (float) ((double) displayRadius * Math.sin(topRightRadians));
      float y = badgeCenterY + (float) ((double) displayRadius * Math.cos(topRightRadians));
      counter.draw(c, x, y, Gravity.RIGHT, 1f);
    }

    @Override
    public void attach () {
      avatarReceiver.attach();
    }

    @Override
    public void detach () {
      avatarReceiver.detach();
    }

    @Override
    public void performDestroy () {
      avatarReceiver.destroy();
    }
  }
}
