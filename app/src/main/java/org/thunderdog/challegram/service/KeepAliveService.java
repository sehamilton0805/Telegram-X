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
 * File created on 17/08/2026
 */
package org.thunderdog.challegram.service;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.MainActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccount;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;

import java.util.ArrayList;

// Persistent background connection: a sticky foreground service that holds a
// reference on every active account's TDLib instance so sockets stay open and
// notifications are generated locally - independent of FCM delivery and of the
// server's push suppression while the account is online on another device.
// Without a held reference TDLib closes the client ~5 seconds after the last
// activity is destroyed (Tdlib.shouldPause + getPauseTimeout)
public class KeepAliveService extends Service {
  private static final int NOTIFICATION_ID = Integer.MAX_VALUE - 6;

  private final ArrayList<Tdlib> heldTdlibs = new ArrayList<>();
  private boolean isForeground;

  @Nullable
  @Override
  public IBinder onBind (Intent intent) {
    return null;
  }

  @Override
  public int onStartCommand (Intent intent, int flags, int startId) {
    UI.initApp(getApplicationContext());
    if (!Settings.instance().needKeepAliveService()) {
      releaseReferences();
      stopForeground(true);
      stopSelf();
      return START_NOT_STICKY;
    }
    startForegroundWithNotification();
    acquireReferences();
    return START_STICKY;
  }

  private void startForegroundWithNotification () {
    NotificationCompat.Builder b = new NotificationCompat.Builder(this, U.getOtherNotificationChannel())
      .setSmallIcon(R.drawable.baseline_sync_24)
      .setContentTitle(Lang.getString(R.string.KeepAliveNotification))
      .setOngoing(true)
      .setShowWhen(false)
      .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), Intents.mutabilityFlags(false)));
    // foregroundServiceType comes from the manifest (specialUse)
    startForeground(NOTIFICATION_ID, b.build());
    isForeground = true;
  }

  private void acquireReferences () {
    for (TdlibAccount account : TdlibManager.instance().getActiveAccounts()) {
      Tdlib tdlib = account.tdlib();
      if (!heldTdlibs.contains(tdlib)) {
        tdlib.incrementUiReferenceCount();
        heldTdlibs.add(tdlib);
      }
    }
  }

  private void releaseReferences () {
    for (Tdlib tdlib : heldTdlibs) {
      tdlib.decrementUiReferenceCount();
    }
    heldTdlibs.clear();
  }

  @Override
  public void onDestroy () {
    releaseReferences();
    if (isForeground) {
      stopForeground(true);
      isForeground = false;
    }
    super.onDestroy();
  }

  public static void ensureRunning (Context context, boolean enabled) {
    Intent intent = new Intent(context, KeepAliveService.class);
    if (enabled) {
      try {
        ContextCompat.startForegroundService(context, intent);
      } catch (Throwable t) {
        // ForegroundServiceStartNotAllowedException on S+ when started from
        // a restricted background context - the next app open retries
        Log.w("KeepAliveService: unable to start foreground service: %s", Log.toString(t));
      }
    } else {
      try {
        context.stopService(intent);
      } catch (Throwable t) {
        Log.w("KeepAliveService: unable to stop service: %s", Log.toString(t));
      }
    }
  }
}
