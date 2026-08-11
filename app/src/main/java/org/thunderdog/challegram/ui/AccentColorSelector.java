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
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.widget.PopupLayout;
import org.thunderdog.challegram.widget.ShadowView;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.android.widget.FrameLayoutFix;

// Bottom-sheet palette of accent color circles: name colors or profile colors.
// Modeled on ChatFolderIconSelector.
public class AccentColorSelector {
  public static final int MODE_NAME = 0;
  public static final int MODE_PROFILE = 1;

  public interface Delegate {
    void onColorClick (int colorId);
  }

  // ListItem value for the "no profile color" cell
  private static final int COLOR_ID_NONE = -1;

  private final Context context;
  private final Delegate delegate;
  private final View popupView;
  private final GridLayoutManager layoutManager;

  private PopupLayout popupLayout;

  private AccentColorSelector (ViewController<?> owner, int mode, int selectedColorId, Delegate delegate) {
    this.context = owner.context();
    this.delegate = delegate;
    Tdlib tdlib = owner.tdlib();

    List<ListItem> items = new ArrayList<>();
    if (mode == MODE_PROFILE) {
      items.add(new ListItem(ListItem.TYPE_CUSTOM_SINGLE).setIntValue(COLOR_ID_NONE));
      for (int colorId : tdlib.availableProfileAccentColorIds()) {
        items.add(new ListItem(ListItem.TYPE_CUSTOM_SINGLE).setIntValue(colorId));
      }
    } else {
      int[] colorIds = tdlib.availableAccentColorIds();
      if (colorIds.length == 0) {
        colorIds = new int[] {0, 1, 2, 3, 4, 5, 6};
      }
      for (int colorId : colorIds) {
        items.add(new ListItem(ListItem.TYPE_CUSTOM_SINGLE).setIntValue(colorId));
      }
    }

    SettingsAdapter popupAdapter = new SettingsAdapter(owner, null, owner) {
      @Override
      protected SettingHolder initCustom (ViewGroup parent) {
        View colorView = new View(parent.getContext()) {
          @Override
          protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
            int size = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(size, size);
          }

          @Override
          protected void onDraw (Canvas canvas) {
            ListItem item = (ListItem) getTag();
            if (item == null) {
              return;
            }
            int colorId = item.getIntValue();
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Screen.dp(16f);
            if (colorId == selectedColorId) {
              canvas.drawCircle(cx, cy, radius + Screen.dp(4f), Paints.getProgressPaint(Theme.getColor(ColorId.iconActive), Screen.dp(2f)));
            }
            if (mode == MODE_PROFILE) {
              if (colorId == COLOR_ID_NONE) {
                canvas.drawCircle(cx, cy, radius, Paints.getProgressPaint(Theme.getColor(ColorId.icon), Screen.dp(1.5f)));
                float slope = radius * .7f;
                canvas.drawLine(cx - slope, cy + slope, cx + slope, cy - slope, Paints.getProgressPaint(Theme.getColor(ColorId.icon), Screen.dp(1.5f)));
                return;
              }
              TdApi.ProfileAccentColor profileColor = tdlib.profileAccentColor(colorId);
              if (profileColor == null) {
                return;
              }
              TdApi.ProfileAccentColors colors = Theme.getDarkFactor() >= .5f ? profileColor.darkThemeColors : profileColor.lightThemeColors;
              int[] palette = colors.paletteColors;
              RectF rectF = Paints.getRectF();
              rectF.set(cx - radius, cy - radius, cx + radius, cy + radius);
              if (palette.length >= 2) {
                canvas.drawArc(rectF, 135f, 180f, true, Paints.fillingPaint(0xFF000000 | palette[0]));
                canvas.drawArc(rectF, -45f, 180f, true, Paints.fillingPaint(0xFF000000 | palette[1]));
              } else if (palette.length == 1) {
                canvas.drawCircle(cx, cy, radius, Paints.fillingPaint(0xFF000000 | palette[0]));
              }
            } else {
              canvas.drawCircle(cx, cy, radius, Paints.fillingPaint(tdlib.accentColor(colorId).getPrimaryColor()));
            }
          }
        };
        Views.setClickable(colorView);
        colorView.setOnClickListener(v -> {
          ListItem item = (ListItem) colorView.getTag();
          if (item != null) {
            delegate.onColorClick(item.getIntValue());
            hide(true);
          }
        });
        RippleSupport.setTransparentSelector(colorView);
        return new SettingHolder(colorView);
      }

      @Override
      protected void setCustom (ListItem item, SettingHolder holder, int position) {
        holder.itemView.setTag(item);
        holder.itemView.invalidate();
      }
    };

    layoutManager = new GridLayoutManager(context, computeSpanCount(Screen.currentWidth()));
    popupAdapter.setItems(items, false);
    RecyclerView recyclerView = new RecyclerView(context);
    recyclerView.setLayoutManager(layoutManager);
    recyclerView.setAdapter(popupAdapter);
    ViewSupport.setThemedBackground(recyclerView, ColorId.background);

    ShadowView shadowView = new ShadowView(context);
    shadowView.setSimpleTopShadow(true);
    owner.addThemeInvalidateListener(shadowView);

    FrameLayoutFix popupView = new FrameLayoutFix(context);
    popupView.addView(shadowView, FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(7f), Gravity.TOP));
    popupView.addView(recyclerView, FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP, 0, Screen.dp(7f), 0, 0));
    popupView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    this.popupView = popupView;
  }

  private void show () {
    int itemCount = layoutManager.getItemCount();
    int spanCount = layoutManager.getSpanCount();
    int rowCount = (itemCount + spanCount - 1) / spanCount;
    int popupHeight = rowCount * (Screen.currentWidth() / spanCount) + Screen.dp(7f);

    popupLayout = new PopupLayout(context) {
      @Override
      protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
        int popupWidth = getDefaultSize(Screen.currentWidth(), widthMeasureSpec);
        layoutManager.setSpanCount(computeSpanCount(popupWidth));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
      }
    };
    popupLayout.init(true);
    popupLayout.setHideKeyboard();
    popupLayout.setNeedRootInsets();
    popupLayout.showSimplePopupView(popupView, popupHeight);
  }

  public void hide (boolean animated) {
    if (popupLayout != null) {
      popupLayout.hideWindow(animated);
      popupLayout = null;
    }
  }

  private int computeSpanCount (int width) {
    int itemSize = Screen.dp(56f);
    return Math.max(width / itemSize, 3);
  }

  public static AccentColorSelector showNameColors (ViewController<?> owner, int selectedColorId, Delegate delegate) {
    AccentColorSelector selector = new AccentColorSelector(owner, MODE_NAME, selectedColorId, delegate);
    selector.show();
    return selector;
  }

  public static AccentColorSelector showProfileColors (ViewController<?> owner, int selectedColorId, Delegate delegate) {
    AccentColorSelector selector = new AccentColorSelector(owner, MODE_PROFILE, selectedColorId, delegate);
    selector.show();
    return selector;
  }
}
