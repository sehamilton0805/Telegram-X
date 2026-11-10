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
 * File created on 12/06/2024
 */

package org.thunderdog.challegram.util.text.quotes;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.RippleDrawable;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextEntity;
import org.thunderdog.challegram.util.text.TextPart;

import me.vkryl.android.util.ViewProvider;
import me.vkryl.core.ColorUtils;

public class QuoteBackground {
  public static final int QUOTE_LEFT_PADDING = 11;
  public static final int QUOTE_RIGHT_PADDING = 27;
  public static final int QUOTE_VERTICAL_PADDING = 2;
  public static final int QUOTE_VERTICAL_MARGIN = 8;

  private static Drawable quoteDrawable;
  private static Drawable expandDrawable;
  private static final RectF tmpRect = new RectF();
  private static final Path barPath = new Path();
  private static final float[] barRadii = new float[8];

  private int topAddition;
  private int bottomAddition;

  // Expandable quotes (TextEntityTypeExpandableBlockQuote) start folded to
  // Text.COLLAPSED_QUOTE_LINE_COUNT lines; a tap on the quote unfolds them.
  // canCollapse: the quote is longer than the limit, so there is something to
  // fold or unfold; collapsed: the lines past the limit are hidden right now
  private boolean canCollapse;
  private boolean collapsed;

  public final Text parent;
  public final TextEntity entity;
  public int partStart;
  public int partEnd;

  public final RectF bounds = new RectF();
  private final RippleDrawable ripple;

  public QuoteBackground (Text text, TextEntity entity) {
    this.parent = text;
    this.entity = entity;

    this.ripple = new RippleDrawable();
  }

  public void setViewProvider (@Nullable ViewProvider viewProvider) {
    ripple.setViewProvider(viewProvider);
  }

  public void performLongPress (View view) {
    ripple.performLongPress(view);
  }

  public void performCancelTouch () {
    ripple.performCancelTouch();
  }

  public boolean contains (int x, int y) {
    return ripple.bounds.contains(x, y);
  }

  public boolean onTouchEvent (View view, MotionEvent e) {
    return ripple.onTouchEvent(view, e, (int) (e.getX() - ripple.bounds.left), (int) (e.getY() - ripple.bounds.top));
  }

  public void setCollapseState (boolean canCollapse, boolean collapsed) {
    this.canCollapse = canCollapse;
    this.collapsed = canCollapse && collapsed;
  }

  public boolean canCollapse () {
    return canCollapse;
  }

  public boolean isCollapsed () {
    return collapsed;
  }

  // A folded quote ends at its last kept line: the blank lines within the
  // limit stay covered, the tail of the quote is gone from the layout
  public void setBottomAdditionLines (int count) {
    this.bottomAddition = Math.max(0, count) * parent.getLineHeight();
  }



  public void calcHeightAddition () {
    final int lineHeight = parent.getLineHeight();
    this.topAddition = TextPart.getAdditionalLinesBefore(parent.getTextPart(partStart)) * lineHeight;

    final int count = TextPart.getAdditionalLinesAfter(parent.getTextPart(partEnd - 1));
    this.bottomAddition = (count == 1 ? 0 : count) * lineHeight;
  }

  public void draw (Canvas c, int startX, int endX, int endXBottomPadding, int startY) {
    if (quoteDrawable == null) {
      quoteDrawable = Drawables.get(R.drawable.baseline_format_quote_close_16);
    }

    for (int a = partStart; a < partEnd; a++) {
      final TextPart part = parent.getTextPart(a);
      final TextPaint paint = parent.getTextPaint(part.getEntity());
      final Paint.FontMetricsInt fm = parent.getFontMetrics(paint.getTextSize());
      final int height = (part.getHeight() == -1 ? fm.descent - fm.ascent : part.getHeight());

      final int x = part.makeX(startX, endX, endXBottomPadding);
      final int y = part.getY() + startY;

      if (a == partStart) {
        bounds.set(x, y, x + (int) part.getWidth(), y + height);
      } else {
        bounds.union(x, y, x + (int) part.getWidth(), y + height);
      }
    }

    bounds.left -= Screen.dp(QUOTE_LEFT_PADDING);
    bounds.top -= Screen.dp(QUOTE_VERTICAL_PADDING) + topAddition;
    bounds.right += Screen.dp(QUOTE_RIGHT_PADDING);
    bounds.bottom += Screen.dp(QUOTE_VERTICAL_PADDING) + bottomAddition;

    /* * */

    // Official look (QuoteSpan.Block.draw + ReplyMessageLine): accent-coloured bar, 10% tint, 4dp outer corners.
    // RippleDrawable paints only the press highlight, so the resting tint is drawn here.
    final int lineColor = parent.getQuoteLineColor();
    final Paint linePaint = Paints.getPorterDuffPaint(lineColor);

    tmpRect.set(bounds);
    barRadii[0] = barRadii[1] = barRadii[6] = barRadii[7] = Screen.dp(3f); // left corners sit under the bar
    barRadii[2] = barRadii[3] = barRadii[4] = barRadii[5] = Screen.dp(4f); // right corners
    barPath.rewind();
    barPath.addRoundRect(tmpRect, barRadii, Path.Direction.CW);
    c.drawPath(barPath, Paints.fillingPaint(ColorUtils.color(0x1a, lineColor))); // alpha replaced: 10% of the accent

    ripple.setBounds((int) bounds.left, (int) bounds.top, (int) bounds.right, (int) bounds.bottom);
    ripple.setRadius(Screen.dp(3f), Screen.dp(4f), Screen.dp(4f), Screen.dp(3f));
    ripple.setMainColor(lineColor, 0.25f);
    ripple.draw(c);

    Drawables.draw(c, quoteDrawable, bounds.right - Screen.dp(19), bounds.top + Screen.dp(3.5f), linePaint);

    if (canCollapse) {
      // Chevron in the bottom-right corner: down while folded, up while unfolded
      if (expandDrawable == null) {
        expandDrawable = Drawables.get(R.drawable.baseline_keyboard_arrow_down_20);
      }
      final float cx = bounds.right - Screen.dp(11f);
      final float cy = bounds.bottom - Screen.dp(10f);
      if (collapsed) {
        Drawables.drawCentered(c, expandDrawable, cx, cy, linePaint);
      } else {
        c.save();
        c.rotate(180f, cx, cy);
        Drawables.drawCentered(c, expandDrawable, cx, cy, linePaint);
        c.restore();
      }
    }

    // 3dp bar rounded on its outer (left) corners only, like the official client
    tmpRect.set(bounds);
    tmpRect.right = bounds.left + Screen.dp(3);
    barRadii[0] = barRadii[1] = barRadii[6] = barRadii[7] = Screen.dp(3f);
    barRadii[2] = barRadii[3] = barRadii[4] = barRadii[5] = 0f;
    barPath.rewind();
    barPath.addRoundRect(tmpRect, barRadii, Path.Direction.CW);
    c.drawPath(barPath, Paints.fillingPaint(lineColor));
  }
}
