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

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.component.base.SettingView;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

// Telegram Stars balance + transaction history of the current user
public class StarTransactionsController extends RecyclerViewController<Void> {
  public StarTransactionsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_starTransactions;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.MyStars);
  }

  private SettingsAdapter adapter;
  private final ArrayList<TdApi.StarTransaction> transactions = new ArrayList<>();
  private TdApi.StarAmount starAmount;
  private String nextOffset = "";
  private boolean isLoading;
  private boolean endReached;
  private boolean initialLoadFinished;

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      public void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        final int itemId = item.getId();
        if (itemId == R.id.btn_myStars) {
          view.setData(starAmount != null ? Strings.buildCounter(starAmount.starCount) + " ⭐" : Lang.getString(R.string.LoadingInformation));
        } else if (itemId == R.id.btn_starTransaction) {
          TdApi.StarTransaction transaction = (TdApi.StarTransaction) item.getData();
          view.setData(Lang.timeOrDateShort(transaction.date, TimeUnit.SECONDS));
        }
      }
    };
    buildCells();
    recyclerView.setAdapter(adapter);
    recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrolled (@NonNull RecyclerView recyclerView, int dx, int dy) {
        if (dy != 0 && initialLoadFinished && !endReached &&
          ((LinearLayoutManager) recyclerView.getLayoutManager()).findLastVisibleItemPosition() >= adapter.getItems().size() - 8) {
          loadMore();
        }
      }
    });
    loadMore();
  }

  private static int transactionTypeRes (TdApi.StarTransactionType type) {
    switch (type.getConstructor()) {
      case TdApi.StarTransactionTypePremiumBotDeposit.CONSTRUCTOR:
      case TdApi.StarTransactionTypeAppStoreDeposit.CONSTRUCTOR:
      case TdApi.StarTransactionTypeGooglePlayDeposit.CONSTRUCTOR:
      case TdApi.StarTransactionTypeFragmentDeposit.CONSTRUCTOR:
        return R.string.StarTxTopUp;
      case TdApi.StarTransactionTypeUserDeposit.CONSTRUCTOR:
        return R.string.StarTxGiftStars;
      case TdApi.StarTransactionTypeGiveawayDeposit.CONSTRUCTOR:
        return R.string.StarTxGiveaway;
      case TdApi.StarTransactionTypeFragmentWithdrawal.CONSTRUCTOR:
        return R.string.StarTxWithdrawal;
      case TdApi.StarTransactionTypeBotInvoicePurchase.CONSTRUCTOR:
        return R.string.StarTxBot;
      case TdApi.StarTransactionTypeChannelSubscriptionPurchase.CONSTRUCTOR:
        return R.string.StarTxChannelSub;
      case TdApi.StarTransactionTypeGiftPurchase.CONSTRUCTOR:
        return R.string.StarTxGift;
      case TdApi.StarTransactionTypeChannelPaidReactionSend.CONSTRUCTOR:
        return R.string.StarTxPaidReaction;
      case TdApi.StarTransactionTypePaidMessageSend.CONSTRUCTOR:
        return R.string.StarTxPaidMessages;
      case TdApi.StarTransactionTypePremiumPurchase.CONSTRUCTOR:
        return R.string.StarTxPremium;
      default:
        return R.string.StarTxOther;
    }
  }

  private static CharSequence transactionTitle (TdApi.StarTransaction transaction) {
    long starCount = transaction.starAmount.starCount;
    String amount = (starCount > 0 ? "+" : "") + starCount + " ⭐";
    return amount + " · " + Lang.getString(transactionTypeRes(transaction.type));
  }

  private void buildCells () {
    ArrayList<ListItem> items = new ArrayList<>();
    if (!initialLoadFinished) {
      items.add(new ListItem(ListItem.TYPE_PROGRESS));
    } else {
      items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_myStars, R.drawable.baseline_premium_star_24, R.string.StarsBalance));
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
      if (transactions.isEmpty()) {
        // TYPE_EMPTY is a MATCH_PARENT-height sole-screen placeholder; below a card use a plain description row
        items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.StarTxEmpty));
      } else {
        items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
        boolean first = true;
        for (TdApi.StarTransaction transaction : transactions) {
          if (first) {
            first = false;
          } else {
            items.add(new ListItem(ListItem.TYPE_SEPARATOR));
          }
          items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_starTransaction, 0, transactionTitle(transaction), false).setData(transaction));
        }
        items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
      }
    }
    adapter.setItems(items, false);
  }

  private void loadMore () {
    if (isLoading || endReached || isDestroyed()) {
      return;
    }
    isLoading = true;
    tdlib.client().send(new TdApi.GetStarTransactions(new TdApi.MessageSenderUser(tdlib.myUserId()), null, null, nextOffset, 50), result -> runOnUiThreadOptional(() -> {
      isLoading = false;
      boolean wasInitial = !initialLoadFinished;
      initialLoadFinished = true;
      if (result.getConstructor() == TdApi.StarTransactions.CONSTRUCTOR) {
        TdApi.StarTransactions starTransactions = (TdApi.StarTransactions) result;
        starAmount = starTransactions.starAmount;
        for (TdApi.StarTransaction transaction : starTransactions.transactions) {
          transactions.add(transaction);
        }
        nextOffset = starTransactions.nextOffset;
        if (nextOffset == null || nextOffset.isEmpty()) {
          endReached = true;
        }
      } else {
        endReached = true;
        UI.showError(result);
      }
      buildCells();
      if (wasInitial) {
        executeScheduledAnimation();
      }
    }));
  }

  @Override
  public boolean needAsynchronousAnimation () {
    return !initialLoadFinished;
  }
}
