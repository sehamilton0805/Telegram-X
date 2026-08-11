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
 * File created on 04/01/2024
 */
package org.thunderdog.challegram.data;

import android.view.View;

import androidx.annotation.NonNull;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.component.popups.ModernActionedLayout;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.StarTransactionsController;

import tgx.td.Td;

public class TGMessageGift extends TGMessageGiveawayBase {
  private final TdApi.MessagePremiumGiftCode premiumGiftCode;
  private final TdApi.MessageGift gift;
  private final TdApi.MessageUpgradedGift upgradedGift;
  private final TdApi.MessageGiveawayPrizeStars prizeStars;

  public TGMessageGift (MessagesManager manager, TdApi.Message msg, @NonNull TdApi.MessagePremiumGiftCode premiumGiftCode) {
    super(manager, msg);
    this.premiumGiftCode = premiumGiftCode;
    this.gift = null;
    this.upgradedGift = null;
    this.prizeStars = null;
  }

  public TGMessageGift (MessagesManager manager, TdApi.Message msg, @NonNull TdApi.MessageGift gift) {
    super(manager, msg);
    this.premiumGiftCode = null;
    this.gift = gift;
    this.upgradedGift = null;
    this.prizeStars = null;
  }

  public TGMessageGift (MessagesManager manager, TdApi.Message msg, @NonNull TdApi.MessageUpgradedGift upgradedGift) {
    super(manager, msg);
    this.premiumGiftCode = null;
    this.gift = null;
    this.upgradedGift = upgradedGift;
    this.prizeStars = null;
  }

  public TGMessageGift (MessagesManager manager, TdApi.Message msg, @NonNull TdApi.MessageGiveawayPrizeStars prizeStars) {
    super(manager, msg);
    this.premiumGiftCode = null;
    this.gift = null;
    this.upgradedGift = null;
    this.prizeStars = prizeStars;
  }

  protected int onBuildContent (int maxWidth) {
    if (gift != null) {
      return buildGiftContent(maxWidth);
    }
    if (upgradedGift != null) {
      return buildUpgradedGiftContent(maxWidth);
    }
    if (prizeStars != null) {
      return buildPrizeStarsContent(maxWidth);
    }
    final boolean isUnclaimed = premiumGiftCode.isUnclaimed;

    content = new Content(maxWidth - Screen.dp(CONTENT_PADDING_DP * 2));

    content.padding(Screen.dp(25));
    content.add(new ContentDrawable(R.drawable.baseline_gift_72));
    content.padding(Screen.dp(25));

    content.add(Lang.boldify(Lang.getString(isUnclaimed ? R.string.GiveawayUnclaimedPrize : R.string.GiveawayCongratulations)), getTextColorSet(), currentViews);
    content.padding(Screen.dp(6));
    content.add(Lang.getString(isUnclaimed ? R.string.GiveawayYouGetUnclaimedPrize : (premiumGiftCode.isFromGiveaway ? R.string.GiveawayYouWon : R.string.GiveawayYouGetGift)), getTextColorSet(), currentViews);
    if (premiumGiftCode.creatorId != null) {
      content.padding(Screen.dp(6));
      content.add(new ContentBubbles(this, maxWidth - Screen.dp(CONTENT_PADDING_DP * 2 + 60)).setOnClickListener(this::onBubbleClick).addChatId(Td.getSenderId(premiumGiftCode.creatorId)));
    }

    content.padding(Screen.dp(BLOCK_MARGIN));
    content.add(Strings.buildMarkdown(this, Lang.plural(isUnclaimed ? R.string.xGiveawayUnclaimedPrizeReceivedInfo : R.string.xGiveawayPrizeReceivedInfo, premiumGiftCode.monthCount)), getTextColorSet(), currentViews);

    invalidateGiveawayReceiver();
    return content.getHeight();
  }

  private int buildGiftContent (int maxWidth) {
    content = new Content(maxWidth - Screen.dp(CONTENT_PADDING_DP * 2));

    content.padding(Screen.dp(25));
    content.add(new ContentSticker(tdlib, gift.gift.sticker, 100));
    content.padding(Screen.dp(25));

    content.add(Lang.boldify(Lang.getString(R.string.Gift)), getTextColorSet(), currentViews);
    if (gift.senderId != null) {
      content.padding(Screen.dp(6));
      content.add(new ContentBubbles(this, maxWidth - Screen.dp(CONTENT_PADDING_DP * 2 + 60)).setOnClickListener(this::onBubbleClick).addChatId(Td.getSenderId(gift.senderId)));
    }
    if (!Td.isEmpty(gift.text)) {
      content.padding(Screen.dp(6));
      content.add(gift.text.text, getTextColorSet(), currentViews);
    }

    content.padding(Screen.dp(BLOCK_MARGIN));
    content.add(Lang.getString(R.string.GiftValue, Strings.buildCounter(gift.gift.starCount)), getTextColorSet(), currentViews);
    if (gift.wasRefunded) {
      content.padding(Screen.dp(6));
      content.add(Lang.getString(R.string.GiftStatusRefunded), getTextColorSet(), currentViews);
    } else if (gift.wasUpgraded) {
      content.padding(Screen.dp(6));
      content.add(Lang.getString(R.string.GiftStatusUpgraded), getTextColorSet(), currentViews);
    } else if (gift.wasConverted) {
      content.padding(Screen.dp(6));
      content.add(Lang.getString(R.string.GiftStatusConverted), getTextColorSet(), currentViews);
    }

    invalidateGiveawayReceiver();
    return content.getHeight();
  }

  private int buildUpgradedGiftContent (int maxWidth) {
    TdApi.UpgradedGift g = upgradedGift.gift;
    content = new Content(maxWidth - Screen.dp(CONTENT_PADDING_DP * 2));

    content.padding(Screen.dp(25));
    content.add(new ContentSticker(tdlib, g.model.sticker, 100));
    content.padding(Screen.dp(25));

    content.add(Lang.boldify(g.title + " #" + g.number), getTextColorSet(), currentViews);
    if (upgradedGift.senderId != null) {
      content.padding(Screen.dp(6));
      content.add(new ContentBubbles(this, maxWidth - Screen.dp(CONTENT_PADDING_DP * 2 + 60)).setOnClickListener(this::onBubbleClick).addChatId(Td.getSenderId(upgradedGift.senderId)));
    }

    content.padding(Screen.dp(BLOCK_MARGIN));
    content.add(Lang.getString(R.string.GiftModel, g.model.name), getTextColorSet(), currentViews);
    content.padding(Screen.dp(6));
    content.add(Lang.getString(R.string.GiftSymbol, g.symbol.name), getTextColorSet(), currentViews);
    content.padding(Screen.dp(6));
    content.add(Lang.getString(R.string.GiftBackdrop, g.backdrop.name), getTextColorSet(), currentViews);

    invalidateGiveawayReceiver();
    return content.getHeight();
  }

  private int buildPrizeStarsContent (int maxWidth) {
    content = new Content(maxWidth - Screen.dp(CONTENT_PADDING_DP * 2));

    content.padding(Screen.dp(25));
    if (prizeStars.sticker != null) {
      content.add(new ContentSticker(tdlib, prizeStars.sticker, 100));
    } else {
      content.add(new ContentDrawable(R.drawable.baseline_premium_star_96));
    }
    content.padding(Screen.dp(25));

    content.add(Lang.boldify(Lang.getString(R.string.GiveawayCongratulations)), getTextColorSet(), currentViews);
    content.padding(Screen.dp(6));
    content.add(Lang.plural(R.string.WonXStars, prizeStars.starCount), getTextColorSet(), currentViews);

    invalidateGiveawayReceiver();
    return content.getHeight();
  }

  private void onBubbleClick (TdApi.MessageSender senderId) {
    tdlib.ui().openChat(controller(), Td.getSenderId(senderId), new TdlibUi.ChatOpenParameters().keepStack().removeDuplicates().openProfileInCaseOfPrivateChat());
  }

  @Override protected String getButtonText () {
    if (gift != null) {
      return Strings.buildCounter(gift.gift.starCount) + " ⭐";
    }
    if (upgradedGift != null) {
      return Lang.getString(R.string.GiftOpenUnique);
    }
    if (prizeStars != null) {
      return Lang.getString(R.string.MyStars);
    }
    return Lang.getString(R.string.OpenGiftLink);
  }

  private boolean loading;

  @Override public void onClick (View view, TGInlineKeyboard keyboard, TGInlineKeyboard.Button button) {
    if (gift != null) {
      StringBuilder hint = new StringBuilder(Lang.getString(R.string.GiftValue, Strings.buildCounter(gift.gift.starCount)));
      if (gift.sellStarCount > 0 && !gift.wasConverted && !gift.wasUpgraded && !gift.wasRefunded) {
        hint.append("\n").append(Lang.getString(R.string.GiftSell, Strings.buildCounter(gift.sellStarCount)));
      }
      if (gift.canBeUpgraded) {
        hint.append("\n").append(Lang.getString(R.string.GiftCanUpgrade));
      }
      context().tooltipManager().builder(view).show(tdlib, hint.toString()).hideDelayed();
      return;
    }
    if (upgradedGift != null) {
      tdlib.ui().openUrl(controller(), "https://t.me/nft/" + upgradedGift.gift.name, new TdlibUi.UrlOpenParameters());
      return;
    }
    if (prizeStars != null) {
      StarTransactionsController c = new StarTransactionsController(context(), tdlib);
      controller().navigateTo(c);
      return;
    }
    if (loading) {
      return;
    }

    loading = true;
    tdlib.send(new TdApi.CheckPremiumGiftCode(premiumGiftCode.code), (info, error) -> UI.post(() -> {
      loading = false;
      if (error != null) {
        UI.showError(error);
      } else {
        ModernActionedLayout.showGiftCode(context().navigation().getCurrentStackItem(), premiumGiftCode.code, premiumGiftCode, info);
      }
    }));
  }

  @Override public boolean onLongClick (View view, TGInlineKeyboard keyboard, TGInlineKeyboard.Button button) {
    return false;
  }
}
