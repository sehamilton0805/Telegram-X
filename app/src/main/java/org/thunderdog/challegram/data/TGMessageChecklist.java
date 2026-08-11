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

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextWrapper;
import org.thunderdog.challegram.widget.SimplestCheckBox;

import me.vkryl.android.util.ClickHelper;

public class TGMessageChecklist extends TGMessage implements ClickHelper.Delegate {
  private static class TaskEntry {
    TdApi.ChecklistTask task;
    TextWrapper text;
    SimplestCheckBox checkBox;
    // Optimistic state after a tap, until the server echoes updateMessageContent
    boolean pending;
    boolean pendingDone;

    boolean isDone () {
      return pending ? pendingDone : task.completedBy != null || task.completionDate != 0;
    }
  }

  private static final int HIGHLIGHT_NONE = -1;

  private final ClickHelper clickHelper = new ClickHelper(this);
  private TdApi.Checklist list;
  private TextWrapper titleText;
  private TaskEntry[] tasks;
  private Text statusText;
  private int highlightIndex = HIGHLIGHT_NONE;

  public TGMessageChecklist (MessagesManager manager, TdApi.Message msg, TdApi.Checklist list) {
    super(manager, msg);
    setChecklist(list);
  }

  private void setChecklist (@NonNull TdApi.Checklist list) {
    this.list = list;
    this.titleText = new TextWrapper(tdlib, list.title, getBiggerTextStyleProvider(), getTextColorSet(), openParameters(), (wrapper, text, specificMedia) -> invalidateTextMediaReceiver(text, specificMedia))
      .addTextFlags(Text.FLAG_ALL_BOLD)
      .setViewProvider(currentViews);
    TaskEntry[] entries = new TaskEntry[list.tasks.length];
    for (int i = 0; i < list.tasks.length; i++) {
      TaskEntry entry = new TaskEntry();
      entry.task = list.tasks[i];
      entry.text = new TextWrapper(tdlib, entry.task.text, getTextStyleProvider(), getTextColorSet(), openParameters(), (wrapper, text, specificMedia) -> invalidateTextMediaReceiver(text, specificMedia))
        .setViewProvider(currentViews);
      entries[i] = entry;
    }
    this.tasks = entries;
    this.statusText = null;
  }

  private int doneCount () {
    int count = 0;
    for (TaskEntry entry : tasks) {
      if (entry.isDone()) {
        count++;
      }
    }
    return count;
  }

  private void updateStatusText () {
    this.statusText = new Text.Builder(doneCount() + " / " + tasks.length, getContentMaxWidth(), Paints.robotoStyleProvider(12f), getDecentColorSet())
      .singleLine()
      .build();
  }

  @Override
  protected void buildContent (int maxWidth) {
    titleText.prepare(maxWidth);
    int taskWidth = maxWidth - Screen.dp(34f);
    for (TaskEntry entry : tasks) {
      entry.text.prepare(taskWidth);
    }
    updateStatusText();
  }

  @Override
  public void requestTextMedia (ComplexReceiver textMediaReceiver) {
    if (tasks == null) {
      textMediaReceiver.clear();
      return;
    }
    int idOffset = Integer.MAX_VALUE / (tasks.length + 1);
    if (titleText != null) {
      titleText.requestMedia(textMediaReceiver, 0, idOffset);
    } else {
      textMediaReceiver.clearReceiversRange(0, idOffset);
    }
    int key = 0;
    for (TaskEntry entry : tasks) {
      key += idOffset;
      if (entry.text != null) {
        entry.text.requestMedia(textMediaReceiver, key, idOffset);
      } else {
        textMediaReceiver.clearReceiversRange(key, key + idOffset);
      }
    }
  }

  private int getTitleHeight () {
    return titleText.getHeight() + Screen.dp(5f);
  }

  private int getTaskHeight (TaskEntry entry) {
    return Math.max(
      Screen.dp(46f),
      Math.max(Screen.dp(8f), Screen.dp(46f) / 2 - entry.text.getLineHeight() / 2) + entry.text.getHeight() + Screen.dp(12f)
    ) + Screen.separatorSize();
  }

  @Override
  protected int getContentHeight () {
    int height = getTitleHeight();
    height += Screen.dp(18f); // status row
    for (TaskEntry entry : tasks) {
      height += getTaskHeight(entry);
    }
    height += Screen.dp(6f);
    if (useBubbles()) {
      height += Screen.dp(8f);
    }
    return height;
  }

  @Override
  protected void drawContent (MessageView view, Canvas c, final int startX, int startY, int maxWidth) {
    final int decentColor = getDecentColor();

    titleText.draw(c, startX, startX + maxWidth, 0, startY, null, 1f, view.getTextMediaReceiver());
    startY += getTitleHeight();

    if (statusText != null) {
      statusText.draw(c, startX, startY);
    }
    startY += Screen.dp(18f);

    final int lineColor = getVerticalLineColor();
    final int contentColor = getVerticalLineContentColor();
    for (int i = 0; i < tasks.length; i++) {
      TaskEntry entry = tasks[i];
      int taskHeight = getTaskHeight(entry);
      int rightX = startX + maxWidth + (useBubbles() ? getBubblePaddingRight() : 0);

      int lineY = startY + taskHeight - Screen.separatorSize();
      c.drawLine(startX + Screen.dp(34f), lineY, rightX, lineY, Paints.getProgressPaint(getSeparatorColor(), Screen.separatorSize()));

      if (highlightIndex == i) {
        c.drawRect(startX - (useBubbles() ? getBubbleContentPadding() : 0), startY, rightX, startY + taskHeight, Paints.fillingPaint(Theme.getColor(getPressColorId())));
      }

      int taskTextY = startY + Math.max(Screen.dp(8f), Screen.dp(46f) / 2 - entry.text.getLineHeight() / 2);
      entry.text.draw(c, startX + Screen.dp(34f), startX + maxWidth, 0, taskTextY, null, 1f, view.getTextMediaReceiver());

      boolean isDone = entry.isDone();
      int boxCx = startX + Screen.dp(12f);
      int boxCy = startY + Screen.dp(22f);
      if (isDone) {
        if (entry.checkBox == null) {
          entry.checkBox = SimplestCheckBox.newInstance(1f, null, lineColor, contentColor, SimplestCheckBox.MODE_NORMAL, 1f);
        }
        SimplestCheckBox.draw(c, boxCx, boxCy, 1f, null, entry.checkBox, lineColor, contentColor, SimplestCheckBox.MODE_NORMAL, 1f);
      } else {
        RectF rectF = Paints.getRectF();
        int boxRadius = Screen.dp(9f) - Screen.dp(1f);
        rectF.set(boxCx - boxRadius, boxCy - boxRadius, boxCx + boxRadius, boxCy + boxRadius);
        int squareRadius = Screen.dp(3f);
        c.drawRoundRect(rectF, squareRadius, squareRadius, Paints.getProgressPaint(decentColor, Screen.dp(1f)));
      }

      startY += taskHeight;
    }
  }

  // Touch

  @Override
  public boolean onTouchEvent (MessageView view, MotionEvent e) {
    return super.onTouchEvent(view, e) || clickHelper.onTouchEvent(view, e);
  }

  @Override
  public boolean performLongPress (View view, float x, float y) {
    clickHelper.cancel(view, x, y);
    return super.performLongPress(view, x, y);
  }

  private int findTaskIndexAt (float x, float y) {
    if (x < 0 || x >= getContentWidth() || tasks.length == 0) {
      return -1;
    }
    int startY = getTitleHeight() + Screen.dp(18f);
    for (int i = 0; i < tasks.length; i++) {
      int taskHeight = getTaskHeight(tasks[i]);
      if (y >= startY && y < startY + taskHeight) {
        return i;
      }
      startY += taskHeight;
    }
    return -1;
  }

  @Override
  public boolean needClickAt (View view, float x, float y) {
    return list.canMarkTasksAsDone && findTaskIndexAt(x - getContentX(), y - getContentY()) != -1;
  }

  @Override
  public void onClickTouchDown (View view, float x, float y) {
    highlightIndex = findTaskIndexAt(x - getContentX(), y - getContentY());
    invalidate();
  }

  @Override
  public void onClickTouchUp (View view, float x, float y) {
    highlightIndex = HIGHLIGHT_NONE;
    invalidate();
  }

  @Override
  public void onClickAt (View view, float x, float y) {
    int index = findTaskIndexAt(x - getContentX(), y - getContentY());
    if (index == -1 || !list.canMarkTasksAsDone) {
      return;
    }
    TaskEntry entry = tasks[index];
    boolean markDone = !entry.isDone();
    entry.pending = true;
    entry.pendingDone = markDone;
    updateStatusText();
    invalidate();
    int[] taskIds = new int[] {entry.task.id};
    tdlib.client().send(new TdApi.MarkChecklistTasksAsDone(msg.chatId, msg.id, markDone ? taskIds : new int[0], markDone ? new int[0] : taskIds), result -> {
      if (result.getConstructor() != TdApi.Ok.CONSTRUCTOR) {
        tdlib.okHandler().onResult(result);
        runOnUiThreadOptional(() -> {
          entry.pending = false;
          updateStatusText();
          invalidate();
        });
      }
    });
  }

  // Updates

  @Override
  protected boolean updateMessageContent (TdApi.Message message, TdApi.MessageContent newContent, boolean isBottomMessage) {
    if (newContent.getConstructor() == TdApi.MessageChecklist.CONSTRUCTOR) {
      setChecklist(((TdApi.MessageChecklist) newContent).list);
      rebuildAndUpdateContent();
      // setChecklist replaced every TextWrapper: without an explicit re-request the receiver
      // keeps the old Texts' media and custom emoji go blank (TGMessagePoll does the same
      // after wrapper replacement in setTranslationResult)
      invalidateTextMediaReceiver();
      return true;
    }
    return false;
  }

  @Override
  protected void onMessageContainerDestroyed () {
    if (tasks != null) {
      for (TaskEntry entry : tasks) {
        if (entry.checkBox != null) {
          entry.checkBox.destroy();
          entry.checkBox = null;
        }
      }
    }
  }
}
