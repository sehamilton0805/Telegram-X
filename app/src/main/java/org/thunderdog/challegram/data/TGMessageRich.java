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
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewParent;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.DoubleImageReceiver;
import org.thunderdog.challegram.loader.ImageReceiver;
import org.thunderdog.challegram.loader.Receiver;
import org.thunderdog.challegram.loader.gif.GifReceiver;
import org.thunderdog.challegram.mediaview.MediaViewController;
import org.thunderdog.challegram.mediaview.data.MediaItem;
import org.thunderdog.challegram.mediaview.data.MediaStack;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.text.BlockSwitches;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSet;
import org.thunderdog.challegram.util.text.TextStyleProvider;
import org.thunderdog.challegram.util.text.TextWrapper;
import org.thunderdog.challegram.widget.SimplestCheckBox;

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
// and a button row becomes a real inline keyboard. Text blocks are drawn as
// what they are: headings in a larger bold face, quotes with their bar, code
// in monospace, lists item by item with hanging markers, tables as tables,
// dividers as lines, collapsible blocks folding on a tap. Blocks the bubble
// has no renderer for (audio, maps, embeds) show as a line of text, and only
// then a tap on text opens the full post in the Instant View engine
public class TGMessageRich extends TGMessage implements MediaWrapper.OnClickListener, me.vkryl.android.util.ClickHelper.Delegate {
  private static final float PAGER_MIN_FLING_DP = 400f; // dp per second
  private static final float PART_SPACING_DP = 10f;
  private static final float PART_SPACING_TIGHT_DP = 4f;
  private static final float HEADING_SPACING_AFTER_DP = 6f;
  private static final float LIST_MARKER_GAP_DP = 6f;
  private static final float LIST_CHECKBOX_GUTTER_DP = 24f;
  private static final float DETAILS_GUTTER_DP = 16f;
  private static final float DIVIDER_MIN_WIDTH_DP = 48f;
  private static final float TABLE_PADDING_HORIZONTAL_DP = 8f;
  private static final float TABLE_PADDING_VERTICAL_DP = 6f;
  private static final float TABLE_PADDING_COMPACT_HORIZONTAL_DP = 5f;
  private static final float TABLE_PADDING_COMPACT_VERTICAL_DP = 3f;
  private static final float TABLE_HEADER_TINT = .10f;
  private static final float TABLE_STRIPE_TINT = .05f;
  private static final float TABLE_FLING_SECONDS = .25f;
  private static final int MAX_TABLE_COLSPAN = 32, MAX_TABLE_ROWSPAN = 128;
  private static final int CHECKBOX_NONE = 0, CHECKBOX_EMPTY = 1, CHECKBOX_CHECKED = 2;
  // Size of a heading over the chat font size, by level (1 is the largest)
  private static final float[] HEADING_SIZE_DIFF = {7f, 5f, 3f, 2f, 1f, 0f};
  private static final TextStyleProvider[] headingStyleProviders = new TextStyleProvider[HEADING_SIZE_DIFF.length];

  // Replaced by the full version when TDLib delivered a truncated message
  private TdApi.RichMessage richMessage;
  private ArrayList<TdApi.PageBlock> mediaBlocks;
  private boolean fullRequested;
  // Global wrapper list, in document order: the viewer stack and
  // auto-download index it; receiver keys are per-wrapper tdlib file ids
  private final ArrayList<MediaWrapper> wrappers = new ArrayList<>();
  private final ArrayList<Part> parts = new ArrayList<>();
  private final me.vkryl.android.util.ClickHelper clickHelper = new me.vkryl.android.util.ClickHelper(this);
  private boolean contentInited;
  // State of the walk over the blocks while the parts are being built
  private BlockContext blockContext = new BlockContext(0);
  // First block of the text run being flushed: quotes the user unfolded in
  // it are remembered under this block
  private @Nullable TdApi.PageBlock textRunAnchor;
  // What the user changed by hand, kept by the blocks themselves and not by
  // their order: folding one block must not move the state of the others.
  // Both survive a rebuild of the parts
  private final java.util.Set<TdApi.PageBlockDetails> toggledDetails = Collections.newSetFromMap(new java.util.IdentityHashMap<TdApi.PageBlockDetails, Boolean>());
  private final java.util.IdentityHashMap<TdApi.PageBlock, BlockSwitches> quoteSwitches = new java.util.IdentityHashMap<>();
  // Where the tables and carousels were scrolled to, carried over a rebuild
  private final java.util.IdentityHashMap<TdApi.PageBlock, Float> savedScrolls = new java.util.IdentityHashMap<>();
  // The message has blocks the bubble shows only as a line of text
  private boolean needsFullView;
  // Media blocks behind the wrappers, same order: the viewer maps a tap by index
  private final ArrayList<TdApi.PageBlock> wrapperBlocks = new ArrayList<>();
  private int layoutContentWidth;
  private final Path arrowPath = new Path();

  private abstract static class Part {
    int y, height;
    // Gap above the part in pixels; negative means the regular part spacing
    int spacingBefore = -1;
  }

  private static class TextPart extends Part {
    final TextWrapper wrapper;
    // Left inset of the text and the width of the gutter inside it: a list
    // item keeps its marker there, a collapsible block its arrow
    int indent, gutter;
    // The inset the last layout could afford
    int layoutIndent;
    @Nullable Text marker;
    int checkBox = CHECKBOX_NONE;
    @Nullable SimplestCheckBox checkBoxFrame;
    // Header of a collapsible block: the block it folds, null otherwise
    @Nullable TdApi.PageBlockDetails details;
    boolean detailsOpen;

    TextPart (TextWrapper wrapper) {
      this.wrapper = wrapper;
    }
  }

  private static class DividerPart extends Part { }

  // Where the blocks being walked live: the left inset inherited from the
  // enclosing lists, the marker that waits for the first text of a list item
  // and the gaps of the parts created there
  private static final class BlockContext {
    final int indent;
    int gutter;
    @Nullable Text marker;
    int checkBox = CHECKBOX_NONE;
    int nextSpacing = -1, followSpacing = -1;

    BlockContext (int indent) {
      this.indent = indent;
    }
  }

  private static final class TableCell {
    final TdApi.PageBlockTableCell cell;
    final int column, row, colspan, rowspan;
    // A cell without a text takes its place in the grid and shows nothing
    final boolean invisible;
    final @Nullable TdApi.FormattedText formattedText;
    @Nullable Text text;
    // Width of the text laid out one word per line, and in one piece
    int minWidth, maxWidth;
    // Position inside the table
    final Rect bounds = new Rect();

    TableCell (TdApi.PageBlockTableCell cell, int column, int row) {
      this.cell = cell;
      this.column = column;
      this.row = row;
      // Spans are whatever the sender put there: kept within what a table
      // in a message can sensibly have
      this.colspan = Math.min(Math.max(1, cell.colspan), MAX_TABLE_COLSPAN);
      this.rowspan = Math.min(Math.max(1, cell.rowspan), MAX_TABLE_ROWSPAN);
      this.invisible = cell.text == null;
      TdApi.FormattedText formatted = cell.text != null ? TD.formattedTextFromRichText(cell.text) : null;
      this.formattedText = formatted != null && !formatted.text.trim().isEmpty() ? formatted : null;
    }
  }

  // A table drawn inside the bubble. The layout is the one of the Instant
  // View table (PageBlockTable): columns take their natural width while the
  // table fits, shrink towards their widest word when it does not, and the
  // table scrolls sideways once even that is too wide
  private class TablePart extends Part implements FactorAnimator.Target {
    final TdApi.PageBlockTable table;
    final TableCell[] cells;
    final int columnCount, rowCount;
    // Visible width of the part and the width of the whole table
    int viewWidth, tableWidth;
    // Columns have their natural width: the table may grow to the message
    boolean natural;
    @Nullable float[] columnWidths;
    int layoutMaxWidth = -1, layoutTextSize = -1;
    float scrollX, flingFrom, flingTo;
    @Nullable FactorAnimator animator;

    TablePart (TdApi.PageBlockTable table) {
      this.table = table;
      ArrayList<TableCell> list = new ArrayList<>();
      // Columns still taken by a cell that spans rows: column -> rows left
      ArrayList<Integer> covered = new ArrayList<>();
      int rowIndex = 0;
      if (table.cells != null) {
        for (TdApi.PageBlockTableCell[] rowCells : table.cells) {
          int column = 0;
          if (rowCells != null) {
            for (TdApi.PageBlockTableCell rawCell : rowCells) {
              if (rawCell == null) {
                continue;
              }
              while (column < covered.size() && covered.get(column) > 0) {
                column++;
              }
              TableCell cell = new TableCell(rawCell, column, rowIndex);
              list.add(cell);
              for (int i = 0; i < cell.colspan; i++) {
                int index = column + i;
                while (covered.size() <= index) {
                  covered.add(0);
                }
                covered.set(index, Math.max(cell.rowspan, covered.get(index)));
              }
              column += cell.colspan;
            }
          }
          rowIndex++;
          for (int i = 0; i < covered.size(); i++) {
            covered.set(i, Math.max(covered.get(i) - 1, 0));
          }
        }
      }
      int columns = 0, rows = 0;
      for (TableCell cell : list) {
        columns = Math.max(columns, cell.column + cell.colspan);
        rows = Math.max(rows, cell.row + cell.rowspan);
      }
      this.cells = list.toArray(new TableCell[0]);
      this.columnCount = columns;
      this.rowCount = rows;
    }

    int paddingHorizontal () {
      return Screen.dp(table.isCompact ? TABLE_PADDING_COMPACT_HORIZONTAL_DP : TABLE_PADDING_HORIZONTAL_DP);
    }

    int paddingVertical () {
      return Screen.dp(table.isCompact ? TABLE_PADDING_COMPACT_VERTICAL_DP : TABLE_PADDING_VERTICAL_DP);
    }

    boolean isScrollable () {
      return tableWidth > viewWidth;
    }

    float clampScroll (float scroll) {
      return Math.max(0f, Math.min(scroll, Math.max(0, tableWidth - viewWidth)));
    }

    void layout (int maxWidth) {
      viewWidth = maxWidth;
      final int textSize = getTextStyleProvider().getTextSizeInPixels();
      if (layoutMaxWidth == maxWidth && layoutTextSize == textSize && columnWidths != null) {
        return;
      }
      layoutMaxWidth = maxWidth;
      layoutTextSize = textSize;
      final int padding = paddingHorizontal() * 2;
      final int maxTextWidth = Math.max(Screen.dp(24f), maxWidth - padding);
      final float[] minWidths = new float[columnCount];
      final float[] maxWidths = new float[columnCount];
      boolean hasMedia = false;
      for (TableCell cell : cells) {
        measureCell(cell, maxTextWidth);
        final Text measuredText = cell.text;
        if (measuredText != null && measuredText.hasMedia()) {
          hasMedia = true;
        }
        for (int column = cell.column; column < cell.column + cell.colspan; column++) {
          minWidths[column] = Math.max(minWidths[column], (float) (cell.minWidth + padding) / cell.colspan);
          maxWidths[column] = Math.max(maxWidths[column], (float) (cell.maxWidth + padding) / cell.colspan);
        }
      }
      float minTotal = 0f, maxTotal = 0f;
      for (int column = 0; column < columnCount; column++) {
        minTotal += minWidths[column];
        maxTotal += maxWidths[column];
      }
      final float[] widths;
      if (maxTotal <= maxWidth) {
        widths = maxWidths;
        natural = true;
      } else if (minTotal <= maxWidth && maxTotal > minTotal) {
        final float room = maxWidth - minTotal;
        final float range = maxTotal - minTotal;
        widths = new float[columnCount];
        for (int column = 0; column < columnCount; column++) {
          widths[column] = minWidths[column] + (maxWidths[column] - minWidths[column]) * room / range;
        }
        natural = false;
      } else {
        widths = minWidths;
        natural = false;
      }
      applyColumnWidths(widths);
      if (hasMedia) {
        // The cell texts are new: their custom emoji have to be requested
        invalidateTextMediaReceiver();
      }
    }

    // Two measures on one text, as the Instant View table does: with every
    // word on its own line the text is as narrow as it can get, in one piece
    // it is as wide as it wants to be
    private void measureCell (TableCell cell, int maxTextWidth) {
      final Text oldText = cell.text;
      Text newText = null;
      if (cell.formattedText != null) {
        int flags = Text.FLAG_ALWAYS_BREAK;
        if (cell.cell.isHeader) {
          flags |= Text.FLAG_ALL_BOLD;
        }
        if (cell.cell.align != null) {
          switch (cell.cell.align.getConstructor()) {
            case TdApi.PageBlockHorizontalAlignmentCenter.CONSTRUCTOR:
              flags |= Text.FLAG_ALIGN_CENTER;
              break;
            case TdApi.PageBlockHorizontalAlignmentRight.CONSTRUCTOR:
              flags |= Text.FLAG_ALIGN_RIGHT;
              break;
          }
        }
        newText = new Text.Builder(tdlib, cell.formattedText, openParameters(), maxTextWidth, getTextStyleProvider(), getTextColorSet(),
          (mediaText, specificMedia) -> invalidateTextMediaReceiver(mediaText, specificMedia))
          .textFlags(flags)
          .viewProvider(currentViews)
          .build();
        cell.minWidth = newText.getWidth();
        newText.setTextFlag(Text.FLAG_ALWAYS_BREAK, false);
        newText.changeMaxWidth(maxTextWidth, true);
        cell.maxWidth = newText.getWidth();
      } else {
        cell.minWidth = cell.maxWidth = 0;
      }
      // The new text takes the place of the old one before that is released:
      // the bubble may be drawn while the layout is being redone
      cell.text = newText;
      if (oldText != null) {
        oldText.performDestroy();
      }
    }

    void applyColumnWidths (float[] widths) {
      columnWidths = widths;
      final int paddingHorizontal = paddingHorizontal();
      final int paddingVertical = paddingVertical();
      final float[] x = new float[columnCount + 1];
      for (int column = 0; column < columnCount; column++) {
        x[column + 1] = x[column] + widths[column];
      }
      final float[] rowHeights = new float[rowCount];
      for (TableCell cell : cells) {
        final int cellWidth = (int) Math.ceil(x[cell.column + cell.colspan] - x[cell.column]);
        int textHeight = 0;
        final Text cellText = cell.text;
        if (cellText != null) {
          // Two pixels of slack, as in the Instant View table: a text as wide
          // as its cell must not wrap because of rounding
          cellText.changeMaxWidth(Math.max(1, cellWidth - paddingHorizontal * 2 + 2));
          textHeight = cellText.getHeight();
        }
        final float cellHeight = (float) (textHeight + paddingVertical * 2) / cell.rowspan;
        for (int row = cell.row; row < cell.row + cell.rowspan; row++) {
          rowHeights[row] = Math.max(rowHeights[row], cellHeight);
        }
      }
      final float[] y = new float[rowCount + 1];
      for (int row = 0; row < rowCount; row++) {
        y[row + 1] = y[row] + rowHeights[row];
      }
      for (TableCell cell : cells) {
        cell.bounds.set(Math.round(x[cell.column]), Math.round(y[cell.row]), Math.round(x[cell.column + cell.colspan]), Math.round(y[cell.row + cell.rowspan]));
      }
      tableWidth = Math.round(x[columnCount]);
      height = Math.round(y[rowCount]);
      scrollX = clampScroll(scrollX);
    }

    // Columns of a table that fits take their natural width; once the width
    // of the whole message is known, the table grows to it
    void stretchTo (int width) {
      final float[] current = columnWidths;
      if (natural && current != null && columnCount > 0 && tableWidth < width) {
        final float add = (float) (width - tableWidth) / columnCount;
        final float[] widths = new float[columnCount];
        for (int column = 0; column < columnCount; column++) {
          widths[column] = current[column] + add;
        }
        applyColumnWidths(widths);
        // A text laid out anew drops its media: the custom emoji of the cells
        // have to be requested again, or they stay blank
        for (TableCell cell : cells) {
          final Text cellText = cell.text;
          if (cellText != null && cellText.hasMedia()) {
            invalidateTextMediaReceiver();
            break;
          }
        }
      }
    }

    void draw (MessageView view, Canvas c, int startX, int top) {
      final boolean clip = isScrollable();
      if (clip) {
        c.save();
        c.clipRect(startX, top, startX + viewWidth, top + height);
      }
      // Cells are drawn at their place on the screen, without translating the
      // canvas: a text remembers where it was drawn and tests taps against it
      final int left = startX - Math.round(scrollX);
      final int paddingHorizontal = paddingHorizontal();
      final int paddingVertical = paddingVertical();
      final int textColor = getTextColor();
      final int headerColor = ColorUtils.alphaColor(TABLE_HEADER_TINT, textColor);
      final int stripeColor = ColorUtils.alphaColor(TABLE_STRIPE_TINT, textColor);
      final int lineColor = getSeparatorColor();
      for (TableCell cell : cells) {
        final int cellLeft = left + cell.bounds.left, cellRight = left + cell.bounds.right;
        final int cellTop = top + cell.bounds.top, cellBottom = top + cell.bounds.bottom;
        if (!cell.invisible) {
          if (cell.cell.isHeader) {
            c.drawRect(cellLeft, cellTop, cellRight, cellBottom, Paints.fillingPaint(headerColor));
          } else if (table.isStriped && cell.row % 2 == 0) {
            c.drawRect(cellLeft, cellTop, cellRight, cellBottom, Paints.fillingPaint(stripeColor));
          }
          if (table.isBordered) {
            c.drawRect(cellLeft, cellTop, cellRight, cellBottom, Paints.strokeSeparatorPaint(lineColor));
          }
        }
        final Text cellText = cell.text;
        if (cellText != null) {
          int textY = cellTop + paddingVertical;
          if (cell.cell.valign != null) {
            switch (cell.cell.valign.getConstructor()) {
              case TdApi.PageBlockVerticalAlignmentMiddle.CONSTRUCTOR:
                textY = cellTop + (cellBottom - cellTop - cellText.getHeight()) / 2;
                break;
              case TdApi.PageBlockVerticalAlignmentBottom.CONSTRUCTOR:
                textY = cellBottom - paddingVertical - cellText.getHeight();
                break;
            }
          }
          cellText.draw(c, cellLeft + paddingHorizontal, cellRight - paddingHorizontal, 0, textY, null, 1f, view.getTextMediaReceiver());
        }
      }
      if (clip) {
        c.restore();
      }
    }

    boolean onTouchEvent (MessageView view, MotionEvent e) {
      if (e.getAction() == MotionEvent.ACTION_DOWN) {
        // Cells scrolled out of sight keep the place they were drawn at
        final float x = e.getX();
        final int left = getContentX();
        if (x < left || x > left + viewWidth) {
          return false;
        }
      }
      for (TableCell cell : cells) {
        final Text cellText = cell.text;
        if (cellText != null && cellText.onTouchEvent(view, e)) {
          return true;
        }
      }
      return false;
    }

    void cancelTouch () {
      for (TableCell cell : cells) {
        final Text cellText = cell.text;
        if (cellText != null) {
          cellText.cancelTouch();
        }
      }
    }

    long requestTextMedia (ComplexReceiver receiver, long nextKey) {
      for (TableCell cell : cells) {
        final Text cellText = cell.text;
        if (cellText != null) {
          cellText.requestMedia(receiver, nextKey, TEXT_MEDIA_KEY_STRIDE);
        }
        nextKey += TEXT_MEDIA_KEY_STRIDE;
      }
      return nextKey;
    }

    void performDestroy () {
      stopFling();
      animator = null;
      for (TableCell cell : cells) {
        final Text cellText = cell.text;
        cell.text = null;
        if (cellText != null) {
          cellText.performDestroy();
        }
      }
      layoutMaxWidth = -1;
    }

    // Inertia after a drag: the table glides to where the fling would take
    // it and slows down on the way
    void fling (float velocityX) {
      final float target = clampScroll(scrollX - velocityX * TABLE_FLING_SECONDS);
      if (target == scrollX) {
        return;
      }
      flingFrom = scrollX;
      flingTo = target;
      if (animator == null) {
        animator = new FactorAnimator(0, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 280l);
      }
      animator.forceFactor(0f);
      animator.animateTo(1f);
    }

    void stopFling () {
      if (animator != null && animator.isAnimating()) {
        animator.forceFactor(animator.getFactor());
      }
    }

    @Override
    public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
      scrollX = clampScroll(flingFrom + (flingTo - flingFrom) * factor);
      invalidate();
    }
  }

  private class MediaPart extends Part implements FactorAnimator.Target {
    final ArrayList<MediaWrapper> pageWrappers = new ArrayList<>();
    // First block of the carousel: its scroll position is remembered under it
    @Nullable TdApi.PageBlock anchor;
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

    ButtonRowPart (TdApi.InlineButton[] buttons, @Nullable TdApi.PageBlockHorizontalAlignment align) {
      TdApi.InlineKeyboardButton[] row = new TdApi.InlineKeyboardButton[buttons.length];
      TdApi.FormattedText[] richLabels = new TdApi.FormattedText[buttons.length];
      boolean hasRichLabels = false;
      for (int i = 0; i < buttons.length; i++) {
        TdApi.InlineButton button = buttons[i];
        TdApi.FormattedText emojiText = buttonLabelText(TD.formattedTextFromRichText(button.text));
        TdApi.TextEntity firstEmoji = null;
        int emojiCount = 0;
        if (emojiText.entities != null) {
          for (TdApi.TextEntity entity : emojiText.entities) {
            if (entity.type.getConstructor() == TdApi.TextEntityTypeCustomEmoji.CONSTRUCTOR) {
              if (firstEmoji == null) {
                firstEmoji = entity;
              }
              emojiCount++;
            }
          }
        }
        long iconCustomEmojiId = 0;
        String label;
        if (emojiCount == 0) {
          label = TD.richTextToString(button.text);
          label = label != null ? label.trim() : "";
        } else if (emojiCount == 1 && isBlankAround(emojiText, firstEmoji)) {
          // A lone custom emoji: an icon-only button (bot grids keep the
          // big centered icon)
          iconCustomEmojiId = ((TdApi.TextEntityTypeCustomEmoji) firstEmoji.type).customEmojiId;
          label = "";
        } else {
          // Words and custom emoji: drawn as rich text with the emoji where
          // the bot put them ("Bestiary: <emoji>"), not as a leading icon
          richLabels[i] = emojiText;
          hasRichLabels = true;
          label = emojiText.text;
        }
        row[i] = new TdApi.InlineKeyboardButton(label.isEmpty() ? " " : label, iconCustomEmojiId, button.style, button.type);
      }
      this.markup = new TdApi.ReplyMarkupInlineKeyboard(new TdApi.InlineKeyboardButton[][] {row}, false);
      this.keyboard = new TGInlineKeyboard(TGMessageRich.this, false);
      this.keyboard.setUseContentTextMedia(true);
      this.keyboard.setRichLabels(hasRichLabels ? richLabels : null);
      this.keyboard.setAlignment(align);
      this.keyboard.setViewProvider(currentViews);
    }
  }

  // Button label: custom emoji and text styles (italic, underline,
  // strikethrough; the whole label is bold anyway) survive, links, mentions,
  // hashtags and code do not - a button is not a link. Trimmed of
  // surrounding whitespace; entities sorted outer-first for the renderer.
  private static TdApi.FormattedText buttonLabelText (TdApi.FormattedText text) {
    String source = text.text != null ? text.text : "";
    int start = 0, end = source.length();
    while (start < end && Character.isWhitespace(source.charAt(start))) {
      start++;
    }
    while (end > start && Character.isWhitespace(source.charAt(end - 1))) {
      end--;
    }
    ArrayList<TdApi.TextEntity> entities = new ArrayList<>();
    if (text.entities != null) {
      for (TdApi.TextEntity entity : text.entities) {
        switch (entity.type.getConstructor()) {
          case TdApi.TextEntityTypeCustomEmoji.CONSTRUCTOR:
            if (entity.offset >= start && entity.offset + entity.length <= end) {
              entities.add(new TdApi.TextEntity(entity.offset - start, entity.length, entity.type));
            }
            break;
          case TdApi.TextEntityTypeItalic.CONSTRUCTOR:
          case TdApi.TextEntityTypeUnderline.CONSTRUCTOR:
          case TdApi.TextEntityTypeStrikethrough.CONSTRUCTOR: {
            int from = Math.max(entity.offset, start);
            int to = Math.min(entity.offset + entity.length, end);
            if (to > from) {
              entities.add(new TdApi.TextEntity(from - start, to - from, entity.type));
            }
            break;
          }
        }
      }
    }
    Collections.sort(entities, (a, b) -> {
      if (a.offset != b.offset) {
        return Integer.compare(a.offset, b.offset);
      }
      if (a.length != b.length) {
        return Integer.compare(b.length, a.length);
      }
      // Same range: the custom emoji goes inside the style, or it is lost
      boolean aEmoji = a.type.getConstructor() == TdApi.TextEntityTypeCustomEmoji.CONSTRUCTOR;
      boolean bEmoji = b.type.getConstructor() == TdApi.TextEntityTypeCustomEmoji.CONSTRUCTOR;
      return Boolean.compare(aEmoji, bEmoji);
    });
    return new TdApi.FormattedText(source.substring(start, end), entities.isEmpty() ? null : entities.toArray(new TdApi.TextEntity[0]));
  }

  // Nothing visible besides the given entity (bots pad icon-only buttons with
  // blank characters because the text may not be empty)
  private static boolean isBlankAround (TdApi.FormattedText text, TdApi.TextEntity entity) {
    String before = text.text.substring(0, entity.offset);
    String after = text.text.substring(entity.offset + entity.length);
    return TGInlineKeyboard.isBlankText(before + after);
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
    blockContext = new BlockContext(0);
    textRunAnchor = null;
    needsFullView = false;
    ArrayList<TdApi.PageBlock> textRun = new ArrayList<>();
    if (richMessage.blocks != null) {
      for (TdApi.PageBlock block : richMessage.blocks) {
        segmentBlock(block, textRun);
      }
    }
    flushTextRun(textRun);
    savedScrolls.clear();
    if (parts.isEmpty()) {
      // Degenerate payload (blocks null/empty, or nothing in them the bubble
      // can show) - a 0x0 content bubble is worse than a placeholder
      addTextPart(new TdApi.FormattedText(Lang.getString(R.string.UnsupportedMessage), null));
    }
  }

  private void flushTextRun (ArrayList<TdApi.PageBlock> textRun) {
    if (textRun.isEmpty()) {
      return;
    }
    final TdApi.PageBlock anchor = textRun.get(0);
    TdApi.FormattedText partText = TD.formattedTextFromPageBlocks(textRun);
    textRun.clear();
    if (partText.text.trim().isEmpty()) {
      return;
    }
    textRunAnchor = anchor;
    addTextPart(partText);
    textRunAnchor = null;
  }

  // Every part goes through here: the gap above it comes from the context
  // the walk is in (list items and the content of a collapsible block sit
  // closer together than top-level blocks)
  private void addPart (Part part) {
    final BlockContext context = blockContext;
    part.spacingBefore = context.nextSpacing;
    context.nextSpacing = context.followSpacing;
    parts.add(part);
  }

  private TextPart addTextPart (TdApi.FormattedText text) {
    return addTextPart(text, getTextStyleProvider(), getTextColorSet(), 0);
  }

  private TextPart addTextPart (TdApi.FormattedText text, TextStyleProvider styleProvider, TextColorSet colorSet, int textFlags) {
    // Custom emoji in the text load asynchronously; the listener re-requests
    // them into the view's content text-media receiver (checklist precedent)
    TextWrapper wrapper = new TextWrapper(tdlib, text, styleProvider, colorSet, openParameters(),
      (textWrapper, richText, specificMedia) -> invalidateTextMediaReceiver(richText, specificMedia))
      .setViewProvider(currentViews);
    if (textFlags != 0) {
      wrapper.addTextFlags(textFlags);
    }
    if (textRunAnchor != null && hasExpandableQuote(text)) {
      wrapper.setBlockSwitches(quoteSwitchesFor(textRunAnchor));
    }
    TextPart part = new TextPart(wrapper);
    final BlockContext context = blockContext;
    part.indent = context.indent;
    part.gutter = context.gutter;
    part.marker = context.marker;
    part.checkBox = context.checkBox;
    context.marker = null;
    context.checkBox = CHECKBOX_NONE;
    addPart(part);
    return part;
  }

  private static boolean hasExpandableQuote (TdApi.FormattedText text) {
    if (text.entities != null) {
      for (TdApi.TextEntity entity : text.entities) {
        if (entity.type.getConstructor() == TdApi.TextEntityTypeExpandableBlockQuote.CONSTRUCTOR) {
          return true;
        }
      }
    }
    return false;
  }

  // Expandable quotes start folded and unfold on a tap inside the text itself
  // (BlockSwitches): the message only follows the size of the text while it
  // changes. The switches are kept under the first block of the text run, so
  // that the choice of the user survives a rebuild of the parts
  private BlockSwitches quoteSwitchesFor (TdApi.PageBlock anchor) {
    BlockSwitches switches = quoteSwitches.get(anchor);
    if (switches == null) {
      switches = new BlockSwitches(changedText -> onQuoteSizeChanged());
      quoteSwitches.put(anchor, switches);
    }
    return switches;
  }

  private void onQuoteSizeChanged () {
    final int keyboardWidth = getInlineKeyboardLayoutWidth();
    rebuildAndUpdateContent();
    rebuildInlineKeyboardIfWidthChanged(keyboardWidth);
  }

  private void toggleDetails (TdApi.PageBlockDetails details) {
    if (!toggledDetails.remove(details)) {
      toggledDetails.add(details);
    }
    rebuildParts();
  }

  // The parts are thrown away and built again from the blocks, the way the
  // arrival of the full version of a truncated message does it
  private void rebuildParts () {
    final int keyboardWidth = getInlineKeyboardLayoutWidth();
    savedScrolls.clear();
    for (Part part : parts) {
      if (part instanceof MediaPart) {
        MediaPart mediaPart = (MediaPart) part;
        // A carousel caught mid-snap is remembered where it was heading
        final float scroll = mediaPart.animator != null && mediaPart.animator.isAnimating() ? mediaPart.snapTo : mediaPart.scrollX;
        if (mediaPart.anchor != null && scroll != 0f) {
          savedScrolls.put(mediaPart.anchor, scroll);
        }
      } else if (part instanceof TablePart) {
        TablePart tablePart = (TablePart) part;
        if (tablePart.scrollX != 0f) {
          savedScrolls.put(tablePart.table, tablePart.scrollX);
        }
      }
    }
    destroyParts();
    dropPagerTouch();
    contentInited = false;
    rebuildAndUpdateContent();
    // The keyboard under the bubble is as wide as the bubble
    rebuildInlineKeyboardIfWidthChanged(keyboardWidth);
    invalidateContentReceiver();
    invalidateTextMediaReceiver();
  }

  private static synchronized TextStyleProvider getHeadingStyleProvider (int level) {
    final int index = Math.max(0, Math.min(HEADING_SIZE_DIFF.length - 1, level - 1));
    TextStyleProvider provider = headingStyleProviders[index];
    if (provider == null) {
      // Follows the chat font size, like the message text itself
      provider = new TextStyleProvider(Fonts.newRobotoStorage()).setTextSizeDiff(HEADING_SIZE_DIFF[index]).setTextSize(Settings.instance().getChatFontSize()).setAllowSp(true);
      Settings.instance().addChatFontSizeChangeListener(provider);
      headingStyleProviders[index] = provider;
    }
    return provider;
  }

  private void segmentHeading (TdApi.PageBlock block, @Nullable TdApi.RichText text, int level, ArrayList<TdApi.PageBlock> textRun) {
    if (text == null || containsButton(text)) {
      // In-text buttons keep their own path
      segmentTextBlock(block, textRun);
      return;
    }
    TdApi.FormattedText formatted = TD.formattedTextFromRichText(text);
    if (formatted.text.trim().isEmpty()) {
      return;
    }
    flushTextRun(textRun);
    addTextPart(formatted, getHeadingStyleProvider(level), getTextColorSet(), Text.FLAG_ALL_BOLD);
    blockContext.nextSpacing = Screen.dp(HEADING_SPACING_AFTER_DP);
  }

  // A list is laid out item by item: each item is a part of its own, inset
  // by the gutter that holds its marker, so wrapped lines hang under the
  // text and nested lists step further in
  private void segmentList (TdApi.PageBlockList list) {
    final BlockContext outer = blockContext;
    final int tightSpacing = Screen.dp(PART_SPACING_TIGHT_DP);
    // Markers first: the gutter is as wide as the widest of them, so that
    // the items of one list share their left edge
    final Text[] markers = new Text[list.items.length];
    int gutter = 0;
    for (int i = 0; i < list.items.length; i++) {
      TdApi.PageBlockListItem item = list.items[i];
      if (item == null) {
        continue;
      }
      if (item.hasCheckbox) {
        gutter = Math.max(gutter, Screen.dp(LIST_CHECKBOX_GUTTER_DP));
      } else {
        markers[i] = new Text.Builder(TD.listItemLabel(item), Screen.dp(240f), getTextStyleProvider(), getTextColorSet()).singleLine().build();
        gutter = Math.max(gutter, markers[i].getWidth() + Screen.dp(LIST_MARKER_GAP_DP));
      }
    }
    boolean first = true;
    for (int i = 0; i < list.items.length; i++) {
      TdApi.PageBlockListItem item = list.items[i];
      if (item == null) {
        continue;
      }
      BlockContext inner = new BlockContext(outer.indent + gutter);
      inner.gutter = gutter;
      inner.marker = markers[i];
      inner.checkBox = item.hasCheckbox ? (item.isChecked ? CHECKBOX_CHECKED : CHECKBOX_EMPTY) : CHECKBOX_NONE;
      inner.nextSpacing = first ? outer.nextSpacing : tightSpacing;
      inner.followSpacing = tightSpacing;
      final int partCount = parts.size();
      blockContext = inner;
      ArrayList<TdApi.PageBlock> itemRun = new ArrayList<>();
      if (item.blocks != null) {
        for (TdApi.PageBlock innerBlock : item.blocks) {
          segmentBlock(innerBlock, itemRun);
        }
      }
      flushTextRun(itemRun);
      blockContext = outer;
      if (parts.size() > partCount) {
        // The gap above the list went to this item
        first = false;
      }
      if (inner.marker != null) {
        // The item had no text to carry the marker
        inner.marker.performDestroy();
        inner.marker = null;
      }
    }
    if (!first) {
      outer.nextSpacing = outer.followSpacing;
    }
  }

  // A collapsible block: a bold header with an arrow, and under it the
  // content while the block is open. A tap on the header toggles it
  private void segmentDetails (TdApi.PageBlockDetails details) {
    final boolean open = details.isOpen != toggledDetails.contains(details);
    TdApi.FormattedText header = TD.formattedTextFromRichText(details.header);
    if (header.text.trim().isEmpty()) {
      header = new TdApi.FormattedText("…", null);
    }
    final BlockContext outer = blockContext;
    final int gutter = Screen.dp(DETAILS_GUTTER_DP);
    BlockContext headerContext = new BlockContext(outer.indent + gutter);
    headerContext.gutter = gutter;
    headerContext.nextSpacing = outer.nextSpacing;
    blockContext = headerContext;
    TextPart headerPart = addTextPart(header, getTextStyleProvider(), getTextColorSet(), Text.FLAG_ALL_BOLD);
    headerPart.details = details;
    headerPart.detailsOpen = open;
    blockContext = outer;
    outer.nextSpacing = outer.followSpacing;
    if (open && details.blocks != null) {
      BlockContext inner = new BlockContext(outer.indent);
      inner.nextSpacing = Screen.dp(PART_SPACING_TIGHT_DP);
      inner.followSpacing = outer.followSpacing;
      blockContext = inner;
      ArrayList<TdApi.PageBlock> innerRun = new ArrayList<>();
      for (TdApi.PageBlock innerBlock : details.blocks) {
        segmentBlock(innerBlock, innerRun);
      }
      flushTextRun(innerRun);
      blockContext = outer;
    }
  }

  // A block that is text and nothing else. Buttons can also live inside the
  // text itself (RichTextButton, the in-message buttons of Bot API 10.3); the
  // flat text extractor drops them, so a text block carrying any becomes its
  // own text / button row / text sequence instead of joining the run
  private void segmentTextBlock (TdApi.PageBlock block, ArrayList<TdApi.PageBlock> textRun) {
    TdApi.RichText blockText = richTextOf(block);
    if (blockText != null && containsButton(blockText)) {
      flushTextRun(textRun);
      segmentRichTextWithButtons(blockText);
    } else {
      textRun.add(block);
    }
  }

  private void addCaptionPart (@Nullable TdApi.PageBlockCaption caption) {
    if (caption == null) {
      return;
    }
    TD.RichTextFormatter formatter = new TD.RichTextFormatter();
    formatter.append(caption.text);
    if (!new TD.RichTextFormatter().append(caption.credit).isBlank()) {
      formatter.newLine().append(caption.credit);
    }
    if (!formatter.isBlank()) {
      addTextPart(formatter.build());
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
          addPart(new ButtonRowPart(buttonRow.buttons, buttonRow.align));
        }
        break;
      }
      case TdApi.PageBlockDetails.CONSTRUCTOR: {
        flushTextRun(textRun);
        segmentDetails((TdApi.PageBlockDetails) block);
        break;
      }
      case TdApi.PageBlockList.CONSTRUCTOR: {
        TdApi.PageBlockList list = (TdApi.PageBlockList) block;
        if (list.items != null && list.items.length > 0) {
          flushTextRun(textRun);
          segmentList(list);
        }
        break;
      }
      case TdApi.PageBlockTable.CONSTRUCTOR: {
        TdApi.PageBlockTable table = (TdApi.PageBlockTable) block;
        TablePart tablePart = new TablePart(table);
        if (tablePart.cells.length > 0) {
          Float savedScroll = savedScrolls.remove(table);
          if (savedScroll != null) {
            tablePart.scrollX = savedScroll;
          }
          flushTextRun(textRun);
          TdApi.FormattedText caption = TD.formattedTextFromRichText(table.caption);
          if (!caption.text.trim().isEmpty()) {
            addTextPart(caption, getTextStyleProvider(), getDecentColorSet(), 0);
            blockContext.nextSpacing = Screen.dp(PART_SPACING_TIGHT_DP);
          }
          addPart(tablePart);
        }
        break;
      }
      case TdApi.PageBlockDivider.CONSTRUCTOR: {
        flushTextRun(textRun);
        addPart(new DividerPart());
        break;
      }
      case TdApi.PageBlockTitle.CONSTRUCTOR:
        segmentHeading(block, ((TdApi.PageBlockTitle) block).title, 1, textRun);
        break;
      case TdApi.PageBlockHeader.CONSTRUCTOR:
        segmentHeading(block, ((TdApi.PageBlockHeader) block).header, 2, textRun);
        break;
      case TdApi.PageBlockSubheader.CONSTRUCTOR:
        segmentHeading(block, ((TdApi.PageBlockSubheader) block).subheader, 3, textRun);
        break;
      case TdApi.PageBlockSectionHeading.CONSTRUCTOR: {
        TdApi.PageBlockSectionHeading heading = (TdApi.PageBlockSectionHeading) block;
        segmentHeading(block, heading.text, heading.size, textRun);
        break;
      }
      case TdApi.PageBlockFooter.CONSTRUCTOR: {
        // Small print: a smaller grey face
        TdApi.RichText footer = ((TdApi.PageBlockFooter) block).footer;
        if (footer == null || containsButton(footer)) {
          segmentTextBlock(block, textRun);
          break;
        }
        TdApi.FormattedText formatted = TD.formattedTextFromRichText(footer);
        if (!formatted.text.trim().isEmpty()) {
          flushTextRun(textRun);
          addTextPart(formatted, getSmallerTextStyleProvider(), getDecentColorSet(), 0);
        }
        break;
      }
      case TdApi.PageBlockAudio.CONSTRUCTOR:
      case TdApi.PageBlockVoiceNote.CONSTRUCTOR:
      case TdApi.PageBlockMap.CONSTRUCTOR:
      case TdApi.PageBlockEmbedded.CONSTRUCTOR:
      case TdApi.PageBlockEmbeddedPost.CONSTRUCTOR:
      case TdApi.PageBlockRelatedArticles.CONSTRUCTOR:
      case TdApi.PageBlockChatLink.CONSTRUCTOR:
        // Shown as a line of text here; the full view has the real thing
        needsFullView = true;
        textRun.add(block);
        break;
      default:
        segmentTextBlock(block, textRun);
        break;
    }
  }

  private static @Nullable TdApi.RichText richTextOf (TdApi.PageBlock block) {
    switch (block.getConstructor()) {
      case TdApi.PageBlockParagraph.CONSTRUCTOR:
        return ((TdApi.PageBlockParagraph) block).text;
      case TdApi.PageBlockPreformatted.CONSTRUCTOR:
        return ((TdApi.PageBlockPreformatted) block).text;
      case TdApi.PageBlockTitle.CONSTRUCTOR:
        return ((TdApi.PageBlockTitle) block).title;
      case TdApi.PageBlockSubtitle.CONSTRUCTOR:
        return ((TdApi.PageBlockSubtitle) block).subtitle;
      case TdApi.PageBlockHeader.CONSTRUCTOR:
        return ((TdApi.PageBlockHeader) block).header;
      case TdApi.PageBlockSubheader.CONSTRUCTOR:
        return ((TdApi.PageBlockSubheader) block).subheader;
      case TdApi.PageBlockFooter.CONSTRUCTOR:
        return ((TdApi.PageBlockFooter) block).footer;
      case TdApi.PageBlockKicker.CONSTRUCTOR:
        return ((TdApi.PageBlockKicker) block).kicker;
      case TdApi.PageBlockSectionHeading.CONSTRUCTOR:
        return ((TdApi.PageBlockSectionHeading) block).text;
    }
    return null;
  }

  private static boolean containsButton (@Nullable TdApi.RichText richText) {
    if (richText == null) {
      return false;
    }
    switch (richText.getConstructor()) {
      case TdApi.RichTextButton.CONSTRUCTOR:
        return true;
      case TdApi.RichTexts.CONSTRUCTOR: {
        TdApi.RichText[] texts = ((TdApi.RichTexts) richText).texts;
        if (texts != null) {
          for (TdApi.RichText inner : texts) {
            if (containsButton(inner)) {
              return true;
            }
          }
        }
        return false;
      }
      case TdApi.RichTextBold.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextBold) richText).text);
      case TdApi.RichTextItalic.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextItalic) richText).text);
      case TdApi.RichTextUnderline.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextUnderline) richText).text);
      case TdApi.RichTextStrikethrough.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextStrikethrough) richText).text);
      case TdApi.RichTextMarked.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextMarked) richText).text);
      case TdApi.RichTextSpoiler.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextSpoiler) richText).text);
      // Remaining wrapper types are all "one nested RichText plus a
      // side field" (link target, entity id, etc.) - same shape FormattedText's
      // IV walker (parseRichText) recurses through, mirrored here so a button
      // nested inside e.g. a link/mention/hashtag span isn't missed and left
      // to silently vanish, same as the bug this whole path exists to fix
      case TdApi.RichTextFixed.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextFixed) richText).text);
      case TdApi.RichTextSubscript.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextSubscript) richText).text);
      case TdApi.RichTextSuperscript.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextSuperscript) richText).text);
      case TdApi.RichTextPhoneNumber.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextPhoneNumber) richText).text);
      case TdApi.RichTextBankCardNumber.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextBankCardNumber) richText).text);
      case TdApi.RichTextBotCommand.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextBotCommand) richText).text);
      case TdApi.RichTextCashtag.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextCashtag) richText).text);
      case TdApi.RichTextDateTime.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextDateTime) richText).text);
      case TdApi.RichTextHashtag.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextHashtag) richText).text);
      case TdApi.RichTextMention.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextMention) richText).text);
      case TdApi.RichTextMentionName.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextMentionName) richText).text);
      case TdApi.RichTextEmailAddress.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextEmailAddress) richText).text);
      case TdApi.RichTextUrl.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextUrl) richText).text);
      case TdApi.RichTextAnchorLink.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextAnchorLink) richText).text);
      case TdApi.RichTextReferenceLink.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextReferenceLink) richText).text);
      case TdApi.RichTextDiff.CONSTRUCTOR:
        return containsButton(((TdApi.RichTextDiff) richText).text);
    }
    return false;
  }

  // Flattens a rich text into a sequence of String chunks and InlineButton
  // items, in reading order; formatting wrappers around a button are
  // transparent, everything else keeps its plain-text representation
  // Pieces are InlineButton items and RichText subtrees (kept whole, with
  // their formatting and custom emoji, whenever they hold no button);
  // only a subtree that contains a button is descended into
  private static void flattenRichText (@Nullable TdApi.RichText richText, List<Object> out) {
    if (richText == null) {
      return;
    }
    if (richText.getConstructor() != TdApi.RichTextButton.CONSTRUCTOR && !containsButton(richText)) {
      out.add(richText);
      return;
    }
    switch (richText.getConstructor()) {
      case TdApi.RichTextButton.CONSTRUCTOR:
        out.add(((TdApi.RichTextButton) richText).button);
        break;
      case TdApi.RichTexts.CONSTRUCTOR: {
        TdApi.RichText[] texts = ((TdApi.RichTexts) richText).texts;
        if (texts != null) {
          for (TdApi.RichText inner : texts) {
            flattenRichText(inner, out);
          }
        }
        break;
      }
      case TdApi.RichTextBold.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextBold) richText).text, out);
        break;
      case TdApi.RichTextItalic.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextItalic) richText).text, out);
        break;
      case TdApi.RichTextUnderline.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextUnderline) richText).text, out);
        break;
      case TdApi.RichTextStrikethrough.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextStrikethrough) richText).text, out);
        break;
      case TdApi.RichTextMarked.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextMarked) richText).text, out);
        break;
      case TdApi.RichTextSpoiler.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextSpoiler) richText).text, out);
        break;
      // Same expanded wrapper set as containsButton above - descend rather
      // than flatten the whole subtree to one opaque string, or a button
      // nested this deep would be found (containsButton) but then never
      // actually extracted as a tappable piece here
      case TdApi.RichTextFixed.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextFixed) richText).text, out);
        break;
      case TdApi.RichTextSubscript.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextSubscript) richText).text, out);
        break;
      case TdApi.RichTextSuperscript.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextSuperscript) richText).text, out);
        break;
      case TdApi.RichTextPhoneNumber.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextPhoneNumber) richText).text, out);
        break;
      case TdApi.RichTextBankCardNumber.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextBankCardNumber) richText).text, out);
        break;
      case TdApi.RichTextBotCommand.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextBotCommand) richText).text, out);
        break;
      case TdApi.RichTextCashtag.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextCashtag) richText).text, out);
        break;
      case TdApi.RichTextDateTime.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextDateTime) richText).text, out);
        break;
      case TdApi.RichTextHashtag.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextHashtag) richText).text, out);
        break;
      case TdApi.RichTextMention.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextMention) richText).text, out);
        break;
      case TdApi.RichTextMentionName.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextMentionName) richText).text, out);
        break;
      case TdApi.RichTextEmailAddress.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextEmailAddress) richText).text, out);
        break;
      case TdApi.RichTextUrl.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextUrl) richText).text, out);
        break;
      case TdApi.RichTextAnchorLink.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextAnchorLink) richText).text, out);
        break;
      case TdApi.RichTextReferenceLink.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextReferenceLink) richText).text, out);
        break;
      case TdApi.RichTextDiff.CONSTRUCTOR:
        flattenRichText(((TdApi.RichTextDiff) richText).text, out);
        break;
      default:
        out.add(richText);
        break;
    }
  }

  private void segmentRichTextWithButtons (TdApi.RichText richText) {
    List<Object> pieces = new ArrayList<>();
    flattenRichText(richText, pieces);
    TD.RichTextFormatter text = new TD.RichTextFormatter();
    ArrayList<TdApi.InlineButton> buttons = new ArrayList<>();
    for (Object piece : pieces) {
      if (piece instanceof TdApi.InlineButton) {
        if (!text.isBlank()) {
          addTextPart(text.build());
        }
        text = new TD.RichTextFormatter();
        buttons.add((TdApi.InlineButton) piece);
      } else {
        TdApi.RichText chunk = (TdApi.RichText) piece;
        if (!buttons.isEmpty()) {
          if (new TD.RichTextFormatter().append(chunk).isBlank()) {
            // whitespace between buttons keeps the row together
            continue;
          }
          addPart(new ButtonRowPart(buttons.toArray(new TdApi.InlineButton[0]), null));
          buttons.clear();
        }
        text.append(chunk);
      }
    }
    if (!text.isBlank()) {
      addTextPart(text.build());
    }
    if (!buttons.isEmpty()) {
      addPart(new ButtonRowPart(buttons.toArray(new TdApi.InlineButton[0]), null));
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
    wrapperBlocks.add(mediaBlock);
    return wrapper;
  }

  private void addCarouselPart (List<TdApi.PageBlock> blocks) {
    MediaPart part = new MediaPart();
    part.anchor = blocks.isEmpty() ? null : blocks.get(0);
    if (part.anchor != null) {
      Float savedScroll = savedScrolls.remove(part.anchor);
      if (savedScroll != null) {
        part.scrollX = savedScroll;
      }
    }
    for (TdApi.PageBlock mediaBlock : blocks) {
      MediaWrapper wrapper = registerWrapper(mediaBlock);
      if (wrapper != null) {
        wrapper.setNeedRound(true, true, true, true);
        part.pageWrappers.add(wrapper);
      }
    }
    if (!part.pageWrappers.isEmpty()) {
      addPart(part);
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
      addPart(part);
    }
  }

  // Parts are built once, on whichever comes first: layout, or the text-media
  // request issued at bind time (which precedes layout - without this the
  // button rows' icons would never be requested for a freshly bound message)
  private void ensureParts () {
    if (!contentInited) {
      contentInited = true;
      buildParts();
      ensureFullRichMessage();
    }
  }

  @Override
  protected void buildContent (int maxWidth) {
    ensureParts();
    for (Part part : parts) {
      if (part instanceof TextPart) {
        TextPart textPart = (TextPart) part;
        // Deep nesting must leave room for the text itself
        textPart.layoutIndent = Math.min(textPart.indent, Math.max(0, maxWidth - Screen.dp(96f)));
        textPart.wrapper.prepare(Math.max(Screen.dp(24f), maxWidth - textPart.layoutIndent));
        part.height = textPart.wrapper.getHeight();
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
      } else if (part instanceof TablePart) {
        ((TablePart) part).layout(maxWidth);
      } else if (part instanceof DividerPart) {
        part.height = Math.max(1, Screen.dp(1f));
      }
    }
    // The width of the message is known now: tables that fit grow to it
    final int contentWidth = computeContentWidth();
    for (Part part : parts) {
      if (part instanceof TablePart) {
        ((TablePart) part).stretchTo(Math.min(contentWidth, maxWidth));
      }
    }
    layoutContentWidth = contentWidth;
    int y = 0;
    boolean first = true;
    for (Part part : parts) {
      if (!first) {
        y += part.spacingBefore >= 0 ? part.spacingBefore : Screen.dp(PART_SPACING_DP);
      }
      first = false;
      part.y = y;
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
    return computeContentWidth();
  }

  private int computeContentWidth () {
    int width = 0;
    for (Part part : parts) {
      if (part instanceof TextPart) {
        TextPart textPart = (TextPart) part;
        width = Math.max(width, textPart.layoutIndent + textPart.wrapper.getWidth());
      } else if (part instanceof MediaPart) {
        width = Math.max(width, ((MediaPart) part).pagerWidth);
      } else if (part instanceof MosaicPart) {
        width = Math.max(width, ((MosaicPart) part).mosaic.getWidth());
      } else if (part instanceof ButtonRowPart) {
        width = Math.max(width, ((ButtonRowPart) part).keyboard.getWidth());
      } else if (part instanceof TablePart) {
        TablePart tablePart = (TablePart) part;
        width = Math.max(width, Math.min(tablePart.tableWidth, tablePart.viewWidth));
      } else if (part instanceof DividerPart) {
        width = Math.max(width, Screen.dp(DIVIDER_MIN_WIDTH_DP));
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

  // Same flattened text TD.textFromRichMessage always produced for this
  // message - MessageView's copy-text action falls back to this for any
  // TGMessageText the same way TD.canCopyText() can't (RichMessage isn't a
  // case in Td.textOrCaption); mirrored here so that fallback still works
  // now that text-only rich messages are TGMessageRich too
  public TdApi.FormattedText getText () {
    return TD.textFromRichMessage(richMessage);
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
        TextPart textPart = (TextPart) part;
        if (textPart.gutter > 0) {
          drawGutter(textPart, c, startX, top);
        }
        textPart.wrapper.draw(c, startX + textPart.layoutIndent, startX + maxWidth, 0, top, null, 1f, view.getTextMediaReceiver());
      } else if (part instanceof MediaPart) {
        drawMediaPart((MediaPart) part, view, c, startX, top, receiver);
      } else if (part instanceof MosaicPart) {
        ((MosaicPart) part).mosaic.draw(view, c, startX, top, receiver, false);
      } else if (part instanceof ButtonRowPart) {
        ((ButtonRowPart) part).keyboard.draw(view, c, startX, top);
      } else if (part instanceof TablePart) {
        ((TablePart) part).draw(view, c, startX, top);
      } else if (part instanceof DividerPart) {
        c.drawRect(startX, top, startX + Math.max(layoutContentWidth, Screen.dp(DIVIDER_MIN_WIDTH_DP)), top + part.height, Paints.fillingPaint(getSeparatorColor()));
      }
    }
  }

  // What sits left of an inset text: the arrow of a collapsible block, the
  // checkbox or the marker of a list item
  private void drawGutter (TextPart part, Canvas c, int startX, int top) {
    final int gutterLeft = Math.max(startX, startX + part.layoutIndent - part.gutter);
    final int lineHeight = part.wrapper.getLineHeight();
    final int centerY = top + (lineHeight > 0 ? lineHeight : part.height) / 2;
    if (part.details != null) {
      final float centerX = gutterLeft + Screen.dp(5f);
      final float size = Screen.dp(4f);
      arrowPath.reset();
      if (part.detailsOpen) {
        arrowPath.moveTo(centerX - size, centerY - size / 2f);
        arrowPath.lineTo(centerX + size, centerY - size / 2f);
        arrowPath.lineTo(centerX, centerY + size * .75f);
      } else {
        arrowPath.moveTo(centerX - size / 2f, centerY - size);
        arrowPath.lineTo(centerX + size * .75f, centerY);
        arrowPath.lineTo(centerX - size / 2f, centerY + size);
      }
      arrowPath.close();
      c.drawPath(arrowPath, Paints.fillingPaint(getDecentColor()));
    } else if (part.checkBox == CHECKBOX_CHECKED) {
      final int lineColor = getVerticalLineColor();
      final int contentColor = getVerticalLineContentColor();
      if (part.checkBoxFrame == null) {
        part.checkBoxFrame = SimplestCheckBox.newInstance(1f, null, lineColor, contentColor, SimplestCheckBox.MODE_NORMAL, 1f);
      }
      SimplestCheckBox.draw(c, gutterLeft + Screen.dp(9f), centerY, 1f, null, part.checkBoxFrame, lineColor, contentColor, SimplestCheckBox.MODE_NORMAL, 1f);
    } else if (part.checkBox == CHECKBOX_EMPTY) {
      final int centerX = gutterLeft + Screen.dp(9f);
      final int boxRadius = Screen.dp(9f) - Screen.dp(1f);
      final int cornerRadius = Screen.dp(3f);
      RectF rectF = Paints.getRectF();
      rectF.set(centerX - boxRadius, centerY - boxRadius, centerX + boxRadius, centerY + boxRadius);
      c.drawRoundRect(rectF, cornerRadius, cornerRadius, Paints.getProgressPaint(getDecentColor(), Screen.dp(1f)));
    } else if (part.marker != null) {
      part.marker.draw(c, Math.max(startX, startX + part.layoutIndent - Screen.dp(LIST_MARKER_GAP_DP) - part.marker.getWidth()), top);
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

  private @Nullable Part findScrollablePartAt (float x, float y) {
    Part part = findPartAt(x, y);
    if (part instanceof MediaPart) {
      return ((MediaPart) part).pageWrappers.size() > 1 ? part : null;
    }
    if (part instanceof TablePart) {
      // A table that fits takes no gesture: swipe-to-reply stays available
      return ((TablePart) part).isScrollable() ? part : null;
    }
    return null;
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

  private @Nullable Part touchPart;
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
        Part part = findScrollablePartAt(e.getX(), e.getY());
        if (part != null) {
          touchPart = part;
          pagerDragging = false;
          pagerTouchStartX = e.getX();
          pagerTouchStartY = e.getY();
          if (part instanceof MediaPart) {
            MediaPart mediaPart = (MediaPart) part;
            if (mediaPart.animator != null && mediaPart.animator.isAnimating()) {
              // freeze the snap mid-flight so the finger catches the pager
              mediaPart.animator.forceFactor(mediaPart.animator.getFactor());
            }
            pagerStartScroll = mediaPart.scrollX;
          } else {
            TablePart tablePart = (TablePart) part;
            tablePart.stopFling();
            pagerStartScroll = tablePart.scrollX;
          }
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
        Part part = touchPart;
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
            if (part instanceof TablePart) {
              // The drag owns the gesture from here on: a link pressed on
              // touch-down must not stay highlighted
              ((TablePart) part).cancelTouch();
            }
          }
        }
        if (pagerDragging) {
          float scroll = pagerStartScroll + (pagerTouchStartX - e.getX());
          if (part instanceof MediaPart) {
            MediaPart mediaPart = (MediaPart) part;
            mediaPart.scrollX = mediaPart.clampScroll(scroll);
          } else {
            TablePart tablePart = (TablePart) part;
            tablePart.scrollX = tablePart.clampScroll(scroll);
          }
          invalidate();
          return true;
        }
        return false;
      }
      case MotionEvent.ACTION_UP: {
        Part part = touchPart;
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
          if (part instanceof MediaPart) {
            MediaPart mediaPart = (MediaPart) part;
            mediaPart.snapToPage(findSnapTarget(mediaPart, velocityX));
          } else if (Math.abs(velocityX) >= Screen.dp(PAGER_MIN_FLING_DP)) {
            ((TablePart) part).fling(velocityX);
          }
          return true;
        }
        return false;
      }
      case MotionEvent.ACTION_CANCEL: {
        Part part = touchPart;
        if (part == null) {
          return false;
        }
        boolean wasDragging = pagerDragging;
        dropPagerTouch();
        if (wasDragging && part instanceof MediaPart) {
          MediaPart mediaPart = (MediaPart) part;
          mediaPart.snapToPage(mediaPart.currentPageIndex());
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
      if (part instanceof TablePart && ((TablePart) part).onTouchEvent(view, e)) {
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
    if (part instanceof TextPart) {
      // Text has something to do on a tap only as the header of a collapsible
      // block, or when the bubble shows some block as a plain line and the
      // full view has more. Otherwise the tap is the chat's, as on any text
      return ((TextPart) part).details != null || needsFullView;
    }
    if (part instanceof TablePart) {
      // Claims the touch stream, or the moves of a drag would never arrive
      return ((TablePart) part).isScrollable();
    }
    return part instanceof MediaPart || part instanceof MosaicPart;
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
      TextPart textPart = (TextPart) part;
      if (textPart.details != null) {
        toggleDetails(textPart.details);
      } else if (needsFullView) {
        openFullView();
      }
    }
  }

  // TDLib delivers large rich messages truncated (isFull == false): the
  // preview blocks arrive, the rest - e.g. a 64-button grid - only through
  // getFullRichMessage. Fetching it on tap alone left such messages half
  // rendered, so the full version is requested as soon as the content is
  // first built and the parts are rebuilt in place when it lands

  private void ensureFullRichMessage () {
    if (richMessage.isFull || fullRequested) {
      return;
    }
    fullRequested = true;
    tdlib.client().send(new TdApi.GetFullRichMessage(msg.chatId, msg.id), result -> runOnUiThreadOptional(() -> {
      if (result.getConstructor() == TdApi.RichMessage.CONSTRUCTOR) {
        applyFullRichMessage((TdApi.RichMessage) result);
      }
    }));
  }

  private void applyFullRichMessage (TdApi.RichMessage full) {
    this.richMessage = full;
    this.mediaBlocks = collectMediaBlocks(full);
    // New wrappers need their files requested into every attached view
    rebuildParts();
  }

  // MediaPart/MosaicPart tiles are covered by the wrappers loop (same
  // MediaWrapper instances); TextWrapper and TGInlineKeyboard hold their own
  // Text objects that need their own teardown, same as TGMessage.onDestroy()
  // already does for the outer reply-markup keyboard
  private void destroyParts () {
    for (MediaWrapper wrapper : wrappers) {
      wrapper.destroy();
    }
    wrappers.clear();
    wrapperBlocks.clear();
    for (Part part : parts) {
      if (part instanceof TextPart) {
        TextPart textPart = (TextPart) part;
        textPart.wrapper.performDestroy();
        if (textPart.marker != null) {
          textPart.marker.performDestroy();
          textPart.marker = null;
        }
        if (textPart.checkBoxFrame != null) {
          textPart.checkBoxFrame.destroy();
          textPart.checkBoxFrame = null;
        }
      } else if (part instanceof ButtonRowPart) {
        ((ButtonRowPart) part).keyboard.performDestroy();
      } else if (part instanceof TablePart) {
        ((TablePart) part).performDestroy();
      }
    }
    parts.clear();
  }

  @Override
  protected void onMessageContainerDestroyed () {
    // Otherwise every wrapper/TextWrapper/keyboard this message ever built
    // outlives it - same gap applyFullRichMessage's own rebuild had to guard
    destroyParts();
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
    // Synthetic page for the native Instant View engine.
    // The controller only reads url/displayUrl/siteName from the link preview
    TdApi.WebPageInstantView instantView = new TdApi.WebPageInstantView(rich.blocks, 0, 2, rich.isRtl, true, null);
    TdApi.LinkPreview linkPreview = new TdApi.LinkPreview();
    linkPreview.url = "";
    linkPreview.displayUrl = "";
    linkPreview.siteName = tdlib.senderName(msg.senderId, true);
    org.thunderdog.challegram.ui.InstantViewController controller = new org.thunderdog.challegram.ui.InstantViewController(context(), tdlib);
    controller.setArguments(new org.thunderdog.challegram.ui.InstantViewController.Args(linkPreview, instantView, null));
    try {
      controller.show();
    } catch (Throwable t) {
      // A block the page engine does not know must not take the app down
      Log.e("Unable to open the full view of a rich message", t);
      UI.showToast(R.string.InstantViewUnsupported, Toast.LENGTH_SHORT);
      controller.destroy();
    }
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
    for (TdApi.PageBlock block : wrapperBlocks) {
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
    // wrappers and items share the ordering of wrapperBlocks - indexOf maps tap to page
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

  // The content text-media receiver carries the button rows' custom emoji
  // icons (text parts have no entities of their own); keys are offset per
  // keyboard so many rows can share one receiver

  // Key budget per text part: its custom emoji occupy [nextKey, nextKey + stride)
  private static final long TEXT_MEDIA_KEY_STRIDE = 1024;

  @Override
  public void requestTextMedia (ComplexReceiver textMediaReceiver) {
    ensureParts();
    long nextKey = 0;
    for (Part part : parts) {
      if (part instanceof ButtonRowPart) {
        nextKey = ((ButtonRowPart) part).keyboard.requestTextMedia(textMediaReceiver, (int) nextKey);
      } else if (part instanceof TextPart) {
        ((TextPart) part).wrapper.requestMedia(textMediaReceiver, nextKey, TEXT_MEDIA_KEY_STRIDE);
        nextKey += TEXT_MEDIA_KEY_STRIDE;
      } else if (part instanceof TablePart) {
        nextKey = ((TablePart) part).requestTextMedia(textMediaReceiver, nextKey);
      }
    }
    textMediaReceiver.clearReceiversWithHigherKey(nextKey);
  }
}
