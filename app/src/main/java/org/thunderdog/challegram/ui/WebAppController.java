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
 * File created on 12/08/2026
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.DoubleHeaderView;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;

import me.vkryl.android.widget.FrameLayoutFix;

// Telegram Mini App host. The page talks to the client through the same
// TelegramWebviewProxy.postEvent bridge the game webviews use; the client
// answers via window.Telegram.WebView.receiveEvent
public class WebAppController extends WebkitController<WebAppController.Args> {
  public static class Args {
    public final long botUserId;
    public final long chatId;
    public final String botName;
    public final long launchId;
    public final String url;
    // Non-null only for keyboard-button-launched apps: enables web_app_send_data
    public final @Nullable String buttonText;

    public Args (long botUserId, long chatId, String botName, long launchId, String url, @Nullable String buttonText) {
      this.botUserId = botUserId;
      this.chatId = chatId;
      this.botName = botName;
      this.launchId = launchId;
      this.url = url;
      this.buttonText = buttonText;
    }
  }

  public WebAppController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_webApp;
  }

  private WebView webView;
  private TextView mainButton;

  @Override
  protected void onCreateWebView (DoubleHeaderView headerCell, WebView webView) {
    this.webView = webView;
    Args args = getArgumentsStrict();
    headerCell.setTitle(args.botName);
    headerCell.setSubtitle(Lang.getString(R.string.WebApp));
    webView.addJavascriptInterface(new WebAppProxy(), "TelegramWebviewProxy");
    loadUrl(args.url);
  }

  private class WebAppProxy {
    @JavascriptInterface
    public void postEvent (String eventType, String eventData) {
      UI.post(() -> {
        if (!isDestroyed()) {
          handleWebAppEvent(eventType, eventData);
        }
      });
    }
  }

  private void handleWebAppEvent (String type, @Nullable String data) {
    switch (type) {
      case "web_app_close": {
        navigateBack();
        break;
      }
      case "web_app_send_data": {
        Args args = getArgumentsStrict();
        if (args.buttonText != null) {
          try {
            String payload = new JSONObject(data).optString("data");
            tdlib.client().send(new TdApi.SendWebAppData(args.botUserId, args.buttonText, payload), tdlib.okHandler());
          } catch (Throwable ignored) { }
          navigateBack();
        }
        break;
      }
      case "web_app_open_link": {
        try {
          String url = new JSONObject(data).optString("url");
          if (url != null && !url.isEmpty()) {
            UI.openUrl(url);
          }
        } catch (Throwable ignored) { }
        break;
      }
      case "web_app_open_tg_link": {
        try {
          String path = new JSONObject(data).optString("path_full");
          if (path != null && !path.isEmpty()) {
            tdlib.ui().openUrl(this, "https://t.me" + path, null);
          }
        } catch (Throwable ignored) { }
        break;
      }
      case "web_app_request_theme": {
        sendEventToPage("theme_changed", "{\"theme_params\":" + themeParamsJson() + "}");
        break;
      }
      case "web_app_request_viewport": {
        sendViewportChanged();
        break;
      }
      case "web_app_setup_main_button": {
        try {
          setupMainButton(new JSONObject(data));
        } catch (Throwable ignored) { }
        break;
      }
      case "web_app_ready":
      default:
        // Expand, haptics, back button etc. - safely ignored in v1
        break;
    }
  }

  private void sendEventToPage (String type, @Nullable String jsonData) {
    if (webView != null) {
      webView.evaluateJavascript("window.Telegram.WebView.receiveEvent(\"" + type + "\", " + (jsonData != null ? jsonData : "null") + ");", null);
    }
  }

  private void sendViewportChanged () {
    if (webView == null) {
      return;
    }
    int heightDp = (int) (webView.getHeight() / Screen.density());
    sendEventToPage("viewport_changed", "{\"height\":" + heightDp + ",\"is_state_stable\":true,\"is_expanded\":true}");
  }

  private void setupMainButton (JSONObject params) {
    boolean isVisible = params.optBoolean("is_visible", false);
    if (mainButton == null) {
      if (!isVisible || webView == null || !(webView.getParent() instanceof ViewGroup)) {
        return;
      }
      mainButton = new TextView(context());
      mainButton.setGravity(Gravity.CENTER);
      mainButton.setTypeface(Fonts.getRobotoMedium());
      mainButton.setTextSize(15f);
      mainButton.setOnClickListener(v -> sendEventToPage("main_button_pressed", null));
      ((ViewGroup) webView.getParent()).addView(mainButton, FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(48f), Gravity.BOTTOM));
    }
    mainButton.setVisibility(isVisible ? android.view.View.VISIBLE : android.view.View.GONE);
    Views.setBottomMargin(webView, isVisible ? Screen.dp(48f) : 0);
    if (isVisible) {
      mainButton.setText(params.optString("text", ""));
      mainButton.setEnabled(params.optBoolean("is_active", true));
      int buttonColor = parseColor(params.optString("color"), Theme.getColor(ColorId.fillingPositive));
      int textColor = parseColor(params.optString("text_color"), Theme.getColor(ColorId.fillingPositiveContent));
      mainButton.setBackgroundColor(buttonColor);
      mainButton.setTextColor(textColor);
    }
  }

  private static int parseColor (@Nullable String hex, int fallback) {
    if (hex == null || hex.isEmpty()) {
      return fallback;
    }
    try {
      return Color.parseColor(hex);
    } catch (Throwable t) {
      return fallback;
    }
  }

  @Override
  public void destroy () {
    Args args = getArguments();
    if (args != null && args.launchId != 0) {
      tdlib.client().send(new TdApi.CloseWebApp(args.launchId), tdlib.okHandler());
    }
    super.destroy();
  }

  // Theme bridge

  private static int rgb (int colorId) {
    return Theme.getColor(colorId) & 0xFFFFFF;
  }

  public static TdApi.ThemeParameters buildThemeParameters () {
    return new TdApi.ThemeParameters(
      rgb(ColorId.filling),                 // backgroundColor
      rgb(ColorId.background),              // secondaryBackgroundColor
      rgb(ColorId.headerBackground),        // headerBackgroundColor
      rgb(ColorId.filling),                 // bottomBarBackgroundColor
      rgb(ColorId.filling),                 // sectionBackgroundColor
      rgb(ColorId.separator),               // sectionSeparatorColor
      rgb(ColorId.text),                    // textColor
      rgb(ColorId.textNeutral),             // accentTextColor
      rgb(ColorId.background_textLight),    // sectionHeaderTextColor
      rgb(ColorId.textLight),               // subtitleTextColor
      rgb(ColorId.textNegative),            // destructiveTextColor
      rgb(ColorId.textLight),               // hintColor
      rgb(ColorId.textLink),                // linkColor
      rgb(ColorId.fillingPositive),         // buttonColor
      rgb(ColorId.fillingPositiveContent)   // buttonTextColor
    );
  }

  private static String hex (int colorId) {
    return String.format("\"#%06x\"", Theme.getColor(colorId) & 0xFFFFFF);
  }

  private static String themeParamsJson () {
    return "{" +
      "\"bg_color\":" + hex(ColorId.filling) +
      ",\"secondary_bg_color\":" + hex(ColorId.background) +
      ",\"header_bg_color\":" + hex(ColorId.headerBackground) +
      ",\"bottom_bar_bg_color\":" + hex(ColorId.filling) +
      ",\"section_bg_color\":" + hex(ColorId.filling) +
      ",\"section_separator_color\":" + hex(ColorId.separator) +
      ",\"text_color\":" + hex(ColorId.text) +
      ",\"accent_text_color\":" + hex(ColorId.textNeutral) +
      ",\"section_header_text_color\":" + hex(ColorId.background_textLight) +
      ",\"subtitle_text_color\":" + hex(ColorId.textLight) +
      ",\"destructive_text_color\":" + hex(ColorId.textNegative) +
      ",\"hint_color\":" + hex(ColorId.textLight) +
      ",\"link_color\":" + hex(ColorId.textLink) +
      ",\"button_color\":" + hex(ColorId.fillingPositive) +
      ",\"button_text_color\":" + hex(ColorId.fillingPositiveContent) +
      "}";
  }
}
