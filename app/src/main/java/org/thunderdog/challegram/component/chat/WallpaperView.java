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
 * File created on 01/04/2017
 */
package org.thunderdog.challegram.component.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.palette.graphics.Palette;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.loader.DoubleImageReceiver;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibCache;
import org.thunderdog.challegram.theme.ChatStyleChangeListener;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.ColorState;
import org.thunderdog.challegram.theme.PropertyId;
import org.thunderdog.challegram.theme.TGBackground;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeChangeListener;
import org.thunderdog.challegram.theme.ThemeDelegate;
import org.thunderdog.challegram.theme.ThemeManager;
import org.thunderdog.challegram.tool.DrawAlgorithms;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;

import tgx.td.Td;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.lambda.Destroyable;

public class WallpaperView extends View implements ThemeChangeListener, ChatStyleChangeListener, FactorAnimator.Target, TdlibCache.MyUserDataChangeListener, Destroyable {
  private final MessagesManager manager;
  private final Tdlib tdlib;

  private DoubleImageReceiver receiver;
  private DoubleImageReceiver preview;

  private @Nullable
  TGBackground wallpaper, previewWallpaper;

  private boolean inSetupMode, inSelfBlurMode, selfBlurValue;

  // Per-chat wallpaper (TdApi.Chat.background). While set, it replaces the
  // global wallpaper in this chat and global wallpaper changes are ignored;
  // when it is removed, the global wallpaper comes back.
  private @Nullable TdApi.ChatBackground chatBackground;
  private @Nullable TGBackground chatWallpaper;
  // Dark-theme dimming that was on screen before the last chat wallpaper
  // change, so the dim overlay cross-fades together with the wallpaper.
  private float previousDimming;

  public WallpaperView (Context context, MessagesManager manager, Tdlib tdlib) {
    super(context);
    this.manager = manager;
    this.tdlib = tdlib;
    this.receiver = new DoubleImageReceiver(this, 0);
  }

  public void initWithSetupMode (boolean inSetupMode) {
    this.inSetupMode = inSetupMode;
    preview = new DoubleImageReceiver(this, 0);
    setWallpaper(tdlib.settings().getWallpaper(Theme.getWallpaperIdentifier()), false);
    ThemeManager.instance().addThemeListener(this);
    ThemeManager.instance().addChatStyleListener(this);
    tdlib.cache().addMyUserListener(this);
  }

  public void initWithCustomWallpaper (TGBackground wallpaper) {
    this.inSetupMode = true;
    preview = new DoubleImageReceiver(this, 0);
    setWallpaper(wallpaper, false);
  }

  public void setSelfBlur (boolean value) {
    inSelfBlurMode = true;
    selfBlurValue = value;
    ThemeManager.instance().addChatStyleListener(this);
  }

  @Override
  public void onMyUserUpdated (final TdApi.User myUser) {
    UI.post(() -> {
      if (myUser != null && scheduledWallpaper != null) {
        setWallpaper(scheduledWallpaper, scheduledWallpaperAnimated);
      }
    });
  }

  private TGBackground scheduledWallpaper;
  private boolean scheduledWallpaperAnimated;

  private void setWallpaper (TGBackground wallpaper, boolean animated) {
    if (wallpaper != null && wallpaper.isEmpty()) {
      wallpaper = null;
    }
    animated = animated && manager.controller().isFocused();
    if (tdlib.myUserId() == 0) {
      scheduledWallpaper = wallpaper;
      scheduledWallpaperAnimated = animated;
      return;
    }
    if (this.wallpaper != wallpaper) {
      if (animated) {
        if (TGBackground.compare(this.wallpaper, wallpaper, false)) {
          return;
        }
        this.previewWallpaper = wallpaper;
        preview.setAnimationDisabled(inSetupMode);
        requestFiles(preview, wallpaper);
        animateChange();
      } else {
        this.wallpaper = wallpaper;
        requestFiles(receiver, wallpaper);
        if (manager.useBubbles() && manager.getAdapter() != null) {
          manager.getAdapter().invalidateAllMessages();
        }
      }
    }
  }

  private void requestFiles (DoubleImageReceiver receiver, TGBackground wallpaper) {
    if (wallpaper != null) {
      wallpaper.requestFiles(receiver, !wallpaper.isPattern());
    } else {
      receiver.requestFile(null, null);
    }
  }

  @Override
  public boolean needsTempUpdates () {
    return false;
  }

  @Override
  public void onThemeChanged (ThemeDelegate oldTheme, ThemeDelegate newTheme) {
    int newUsageIdentifier = Theme.getWallpaperIdentifier(newTheme);
    if (Theme.getWallpaperIdentifier(oldTheme) != newUsageIdentifier) {
      setGlobalWallpaper(tdlib.settings().getWallpaper(newUsageIdentifier), true);
    }
  }

  @Override
  public void onThemePropertyChanged (int themeId, @PropertyId int propertyId, float value, boolean isDefault) {
    switch (propertyId) {
      case PropertyId.WALLPAPER_USAGE_ID:
        setGlobalWallpaper(tdlib.settings().getWallpaper(Theme.getWallpaperIdentifier(themeId)), true);
        break;
    }
  }

  private void setGlobalWallpaper (TGBackground wallpaper, boolean animated) {
    if (chatWallpaper != null) {
      return; // the chat keeps its own wallpaper
    }
    setWallpaper(wallpaper, animated);
  }

  // Chat wallpaper

  public static boolean equalsTo (@Nullable TdApi.ChatBackground a, @Nullable TdApi.ChatBackground b) {
    if (a == b) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    return a.darkThemeDimming == b.darkThemeDimming && Td.equalsTo(a.background, b.background);
  }

  public void setChatBackground (@Nullable TdApi.ChatBackground background, boolean animated) {
    if (inSetupMode) {
      return; // wallpaper picker & preview screens show what they were given
    }
    if (equalsTo(this.chatBackground, background)) {
      return;
    }
    if (this.chatBackground != null && background != null && Td.equalsTo(this.chatBackground.background, background.background)) {
      // Same wallpaper, only the dimming changed: snap to the new value.
      // animateChange() must not be used here - it is the wallpaper swap
      // animator and finishes by replacing `wallpaper` with `previewWallpaper`
      this.chatBackground = background;
      this.previousDimming = targetDimming();
      invalidate();
      return;
    }
    this.previousDimming = currentDimming();
    this.chatBackground = background;
    this.chatWallpaper = background != null ? new TGBackground(tdlib, background.background) : null;
    TGBackground wallpaper = chatWallpaper != null ? chatWallpaper : tdlib.settings().getWallpaper(Theme.getWallpaperIdentifier());
    setWallpaper(wallpaper, animated);
    if (!isAnimatingChanges()) {
      previousDimming = targetDimming();
    }
    invalidate();
  }

  public @Nullable TdApi.ChatBackground getChatBackground () {
    return chatBackground;
  }

  /**
   * Shows the given wallpaper (or the global one when null) in a screen that
   * previews wallpapers, e.g. the per-chat wallpaper picker.
   */
  public void showWallpaper (@Nullable TGBackground wallpaper, boolean animated) {
    setWallpaper(wallpaper != null ? wallpaper : tdlib.settings().getWallpaper(Theme.getWallpaperIdentifier()), animated);
  }

  private static boolean allowsDimming (@Nullable TdApi.ChatBackground background) {
    if (background == null || background.background == null || background.background.type == null) {
      return false;
    }
    // TDLib: "Applied only to Wallpaper and Fill types of background"
    switch (background.background.type.getConstructor()) {
      case TdApi.BackgroundTypeWallpaper.CONSTRUCTOR:
      case TdApi.BackgroundTypeFill.CONSTRUCTOR:
        return true;
    }
    return false;
  }

  private float targetDimming () {
    if (chatBackground != null && allowsDimming(chatBackground)) {
      return chatBackground.darkThemeDimming / 100f;
    }
    return 0f;
  }

  private float currentDimming () {
    float target = targetDimming();
    if (isAnimatingChanges()) {
      return previousDimming + (target - previousDimming) * factor;
    }
    return target;
  }

  @Override
  public void onThemeAutoNightModeChanged (int autoNightMode) { }

  @Override
  public void onThemeColorsChanged (boolean areTemp, ColorState state) {
    requestFiles(receiver, this.wallpaper);
  }

  @Override
  public void onChatStyleChanged (Tdlib tdlib, int newChatStyle) {
    if (this.tdlib == tdlib) {
      requestFiles(receiver, this.wallpaper);
      manager.controller().updateShadowColor();
    }
  }

  @Override
  public void onChatWallpaperChanged (Tdlib tdlib, TGBackground wallpaper, int usageIdentifier) {
    if (this.tdlib == tdlib && usageIdentifier == Theme.getWallpaperIdentifier()) {
      if (inSelfBlurMode) {
        wallpaper = TGBackground.newBlurredWallpaper(tdlib, wallpaper, selfBlurValue);
      }

      setGlobalWallpaper(wallpaper, true);
    }
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    layoutReceivers();
  }

  private void layoutReceivers () {
    int width = getMeasuredWidth();
    int height = Math.max(Screen.currentActualHeight(), getMeasuredHeight());
    receiver.setBounds(0, 0, width, height);
    if (preview != null) {
      preview.setBounds(0, 0, width, height);
    }
  }

  private float factor;
  private FactorAnimator animator;

  private void animateChange () {
    if (animator == null) {
      animator = new FactorAnimator(0, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l);
    } else if (animator.getFactor() == 1f) {
      animator.forceFactor(factor = 0f);
    }
    animator.animateTo(1f);
  }

  private boolean isAnimatingChanges () {
    return animator != null && animator.isAnimating();
  }

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    if (this.factor != factor) {
      this.factor = factor;
      if (manager.controller().inWallpaperMode() && manager.useBubbles() && manager.getAdapter() != null) {
        manager.getAdapter().invalidateAllMessages();
      }
      invalidate();
    }
  }

  public float getBackgroundTransparency () {
    if (!manager.useBubbles())
      return 0f;
    if (animator != null && animator.isAnimating()) {
      if (previewWallpaper == null) {
        return factor;
      } else if (wallpaper == null) {
        return 1f - factor;
      } else {
        return 0f;
      }
    }
    return wallpaper != null ? 0f : 1f;
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {
    if (finalFactor == 1f) {
      wallpaper = previewWallpaper;
      previewWallpaper = null;

      DoubleImageReceiver tempReceiver = receiver;
      receiver = preview;
      receiver.setAnimationDisabled(false);
      preview = tempReceiver;

      factor = 0f;
      previousDimming = targetDimming();

      preview.clear();

      if (manager.useBubbles() && manager.getAdapter() != null) {
        manager.getAdapter().invalidateAllMessages();
      }
      invalidate();
    }
  }

  @Override
  public void performDestroy () {
    receiver.destroy();
    if (preview != null) {
      preview.destroy();
    }
    tdlib.cache().removeMyUserListener(this);
    ThemeManager.instance().removeThemeListener(this);
    ThemeManager.instance().removeChatStyleListener(this);
  }

  private static int getWallpaperBackground (ThemeDelegate theme) {
    return ColorUtils.compositeColor(theme.getColor(ColorId.background), theme.getColor(ColorId.bubble_chatBackground));
  }

  private final DrawAlgorithms.GradientCache gradientCache = new DrawAlgorithms.GradientCache();

  private static void drawWallpaper (TGBackground wallpaper, Canvas c, DrawAlgorithms.GradientCache gradientCache, ThemeDelegate theme, DoubleImageReceiver receiver, float alpha) {
    final int defaultColor = getWallpaperBackground(theme);
    if (wallpaper == null || wallpaper.isEmpty()) {
      c.drawColor(ColorUtils.alphaColor(alpha, defaultColor));
    } else if (wallpaper.isFillSolid()) {
      c.drawColor(ColorUtils.alphaColor(alpha, wallpaper.getBackgroundColor(defaultColor)));
    } else if (wallpaper.isFillGradient()) {
      DrawAlgorithms.drawGradient(c, gradientCache, receiver.getLeft(), receiver.getTop(), receiver.getRight(), receiver.getBottom(), wallpaper.getTopColor(), wallpaper.getBottomColor(), wallpaper.getRotationAngle(), alpha);
    } else if (wallpaper.isFillFreeformGradient()) {
      c.drawColor(ColorUtils.alphaColor(alpha, wallpaper.getBackgroundColor(defaultColor)));
      DrawAlgorithms.drawMulticolorGradient(c, gradientCache, receiver.getLeft(), receiver.getTop(), receiver.getRight(), receiver.getBottom(), wallpaper.getFreeformColors(), alpha);
    } else if (wallpaper.isPattern()) {
      if (wallpaper.isPatternBackgroundGradient()) {
        DrawAlgorithms.drawGradient(c, gradientCache, receiver.getLeft(), receiver.getTop(), receiver.getRight(), receiver.getBottom(), wallpaper.getTopColor(), wallpaper.getBottomColor(), wallpaper.getRotationAngle(), alpha);
      } else if (wallpaper.isPatternBackgroundFreeformGradient()) {
        c.drawColor(ColorUtils.alphaColor(alpha, wallpaper.getBackgroundColor(defaultColor)));
        DrawAlgorithms.drawMulticolorGradient(c, gradientCache, receiver.getLeft(), receiver.getTop(), receiver.getRight(), receiver.getBottom(), wallpaper.getFreeformColors(), alpha);
      } else {
        c.drawColor(ColorUtils.alphaColor(alpha, wallpaper.getBackgroundColor(defaultColor)));
      }
      receiver.getReceiver().setPorterDuffColorFilter(wallpaper.getPatternColor());
      alpha *= wallpaper.getPatternIntensity();
      if (alpha != 1f)
        receiver.setPaintAlpha(alpha);
      receiver.getReceiver().draw(c);
      if (alpha != 1f)
        receiver.restorePaintAlpha();
    } else {
      if (receiver.needPlaceholder()) {
        c.drawColor(ColorUtils.alphaColor(alpha, defaultColor));
      }
      receiver.disablePorterDuffColorFilter();
      if (alpha != 1f) {
        receiver.setPaintAlpha(alpha);
      }
      receiver.draw(c);
      if (alpha != 1f) {
        receiver.restorePaintAlpha();
      }
    }
  }

  public int getDefaultOverlayColor (int defaultColor, boolean text) {
    if (manager.useBubbles()) {
      if (!isAnimatingChanges() || factor == 0f) {
        return getDefaultOverlayColor(wallpaper, receiver, defaultColor, text);
      } else if (factor == 1f) {
        return getDefaultOverlayColor(previewWallpaper, preview, defaultColor, text);
      } else {
        return ColorUtils.fromToArgb(getDefaultOverlayColor(wallpaper, receiver, defaultColor, text), getDefaultOverlayColor(previewWallpaper, preview, defaultColor, text), factor);
      }
    }
    return defaultColor;
  }

  private static int getDefaultOverlayColor (TGBackground background, DoubleImageReceiver receiver, int defaultColor, boolean text) {
    if (background == null || background.isEmpty())
      return defaultColor;
    int previewColor = getColor(background, receiver.getPreview().getCurrentFile(), defaultColor, text);
    int targetColor = getColor(background, receiver.getImageReceiver().getCurrentFile(), defaultColor, text);
    return ColorUtils.fromToArgb(previewColor, targetColor, receiver.getFullLoadFactor());
  }

  private static int getColor (TGBackground background, ImageFile imageFile, int defaultColor, boolean text) {
    if (text)
      return defaultColor;
    int legacyOverlayColor = TGBackground.getLegacyOverlayColor(background.getLegacyWallpaperId(), 0);
    if (legacyOverlayColor != 0)
      return legacyOverlayColor;
    if (background.isPattern())
      return ColorUtils.color((int) ((float) OVERLAY_ALPHA * (.7f + .3f * background.getPatternIntensity())), background.getPatternColor()); // (int) (255f * (.4f + .2f * background.getPatternIntensity()))
    if (background.isFill())
      return ColorUtils.color(OVERLAY_ALPHA, background.getSolidOverlayColor());
    Palette.Swatch swatch = imageFile != null ? imageFile.getPaletteSwatch() : null;
    if (swatch == null)
      return defaultColor;
    return text ? ColorUtils.color(255, swatch.getTitleTextColor()) : ColorUtils.color(OVERLAY_ALPHA, swatch.getRgb());
  }

  public static final int OVERLAY_ALPHA = 0x70;

  @Override
  protected void onDraw (Canvas c) {
    if (manager.useBubbles()) {
      layoutReceivers();
      if (!isAnimatingChanges()) {
        drawWallpaper(wallpaper, c, gradientCache, ThemeManager.instance().currentTheme(), receiver, 1f);
      } else if (factor == 0f) {
        drawWallpaper(wallpaper, c, gradientCache, ThemeManager.instance().previousTheme(), receiver, 1f);
      } else if (factor == 1f) {
        drawWallpaper(previewWallpaper, c, gradientCache, ThemeManager.instance().appliedTheme(), preview, 1f);
      } else {
        drawWallpaper(wallpaper, c, gradientCache, ThemeManager.instance().previousTheme(), receiver, 1f);
        drawWallpaper(previewWallpaper, c, gradientCache, ThemeManager.instance().appliedTheme(), preview, factor);
      }
      // Chat wallpaper dimming in dark themes (chatBackground.darkThemeDimming)
      float dimming = Theme.isDark() ? currentDimming() : 0f;
      if (dimming > 0f) {
        c.drawColor(ColorUtils.alphaColor(dimming, 0xff000000));
      }
    } else {
      c.drawColor(Theme.getColor(ColorId.chatBackground));
    }
  }
}
