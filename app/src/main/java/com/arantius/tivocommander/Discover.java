/*
DVR Commander for TiVo allows control of a TiVo Premiere device.
Copyright (C) 2011  Anthony Lieuallen (arantius@gmail.com)

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License along
with this program; if not, write to the Free Software Foundation, Inc.,
51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/

package com.arantius.tivocommander;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.MulticastLock;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.arantius.tivocommander.rpc.MindRpc;

public class Discover extends ListActivityCompat implements OnItemClickListener,
    ServiceListener, OnItemLongClickListener {
  private static Database db;

  private TextView mEmpty;
  private SimpleAdapter mHostAdapter;
  private volatile ArrayList<HashMap<String, Object>> mHosts =
      new ArrayList<HashMap<String, Object>>();
  private JmDNS mJmdns;
  private MulticastLock mMulticastLock = null;
  private final String mServiceNameRpc = "_tivo-mindrpc._tcp.local.";
  private final String mServiceNameVideos = "_tivo-videos._tcp.local.";
  private final String[] mServiceNames = new String[] {
      mServiceNameRpc, mServiceNameVideos};

  public final void customDevice(View v) {
    editCustomDevice(new Device());
  }

  @SuppressLint("InflateParams")
  public final void editCustomDevice(final Device device) {
    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle("Custom Device");

    final LayoutInflater inflater =
        (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    final View dialogView = inflater.inflate(R.layout.device_custom, null);
    builder.setView(dialogView);

    final EditText input_name = (EditText) dialogView.findViewById(
        R.id.input_name);
    input_name.setText(device.device_name);
    final EditText input_addr = (EditText) dialogView.findViewById(
        R.id.input_addr);
    input_addr.setText(device.addr);
    final EditText input_mak = (EditText) dialogView.findViewById(
        R.id.input_mak);
    input_mak.setText(device.mak);
    final EditText input_tsn = (EditText) dialogView.findViewById(
        R.id.input_tsn);
    input_tsn.setText(device.tsn);
    final EditText input_port = (EditText) dialogView.findViewById(
        R.id.input_port);
    input_port.setText(device.port.toString());

    final OnClickListener onClickListener =
        new OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            device.device_name =
                input_name
                    .getText().toString();
            device.addr = ((EditText) dialogView.findViewById(R.id.input_addr))
                .getText().toString();
            device.mak = ((EditText) dialogView.findViewById(R.id.input_mak))
                .getText().toString();
            device.tsn = ((EditText) dialogView.findViewById(R.id.input_tsn))
                .getText().toString();
            if ("".equals(device.tsn))
              device.tsn = "-";
            final String portStr =
                ((EditText) dialogView.findViewById(R.id.input_port))
                    .getText().toString();
            try {
              device.port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
              device.port = 1413;
            }

            db.saveDevice(device);
            db.switchDevice(device);

            Intent intent = new Intent(Discover.this, NowShowing.class);
            startActivity(intent);
            Discover.this.finish();
          }
        };
    builder.setPositiveButton("OK", onClickListener);
    builder.setNegativeButton("Cancel", null);

    builder.create().show();
  }

  public final boolean onCreateOptionsMenu(Menu menu) {
    Utils.createShortOptionsMenu(menu, this);
    return true;
  }

  public void onItemClick(AdapterView<?> parent, View view, int position,
      long id) {
    final HashMap<String, Object> item = mHosts.get(position);

    int messageId = (Integer) item.get("messageId");
    if (messageId > 0) {
      showWarning(messageId, position);
    } else {
      onItemClickResume(position);
    }
  }

  public void onItemClickResume(int position) {
    final HashMap<String, Object> item = mHosts.get(position);

    final Long deviceId = (Long) item.get("deviceId");
    if (deviceId != null) {
      final Device clickedDevice = db.getDevice(deviceId);
      if (clickedDevice != null && !"".equals(clickedDevice.mak)) {
        db.switchDevice(clickedDevice);
        Intent intent = new Intent(Discover.this, NowShowing.class);
        startActivity(intent);
        this.finish();
        return;
      }
    }

    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle("MAK");
    builder.setMessage(R.string.pref_mak_instructions);

    final EditText makEditText = new EditText(this);
    makEditText.setInputType(InputType.TYPE_CLASS_NUMBER);
    builder.setView(makEditText);

    // TODO: Be DRY vs. the same in customDevice().
    final OnClickListener onClickListener =
        new OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            Device device = db.getNamedDevice(
                (String) item.get("name"), (String) item.get("addr"));
            if (device == null) device = new Device();
            device.device_name = (String) item.get("name");
            device.addr = (String) item.get("addr");
            device.mak = makEditText.getText().toString();
            device.tsn = "-";
            try {
              final String portStr = (String) item.get("port");
              device.port = Integer.parseInt(portStr);
            } catch (ClassCastException e) {
              // I don't know why this is showing up, but handle it anyway.
              device.port = (Integer) item.get("port");
            }

            db.saveDevice(device);
            db.switchDevice(device);

            Intent intent = new Intent(Discover.this, NowShowing.class);
            startActivity(intent);
            Discover.this.finish();
          }
        };
    builder.setPositiveButton("OK", onClickListener);
    builder.setNegativeButton("Cancel", null);

    builder.create().show();
  }

  public boolean onItemLongClick(AdapterView<?> parent, View view,
      int position, long id) {
    final HashMap<String, Object> item = mHosts.get(position);
    final Long deviceId = (Long) item.get("deviceId");
    if (deviceId == null) {
      return false;
    }

    final Device device = db.getDevice(deviceId);
    final ArrayList<String> choices = new ArrayList<String>();
    choices.add("Edit");
    choices.add("Delete");

    ArrayAdapter<String> choicesAdapter =
        new ArrayAdapter<String>(this, android.R.layout.select_dialog_item,
            choices);

    DialogInterface.OnClickListener onClickListener =
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int position) {
            switch (position) {
            case 0:
              editCustomDevice(device);
              break;
            case 1:
              db.deleteDevice(deviceId);
              stopQuery();
              startQuery(null);
              break;
            }
          }
        };

    Builder dialogBuilder = new AlertDialog.Builder(this);
    dialogBuilder.setTitle("Operation?");
    dialogBuilder.setAdapter(choicesAdapter, onClickListener);
    dialogBuilder.create().show();

    return true;
  }

  /** ServiceListener */
  public void serviceAdded(ServiceEvent event) {
    // Make sure serviceResolved() gets called.
    event.getDNS().requestServiceInfo(event.getType(), event.getName());
  }

  /** ServiceListener */
  public void serviceRemoved(ServiceEvent event) {
    // Ignore.
  }

  /** ServiceListener */
  public void serviceResolved(ServiceEvent event) {
    ServiceInfo info = event.getInfo();
    Utils.log("Discovery serviceResolved(): " + event.toString());
    if (mJmdns == null) {
      Utils.log("Ignoring because search is not running.");
      return;
    }

    checkDevice(
        event.getName().replaceAll(" \\(\\d\\)$", ""),
        info.getHostAddresses()[0],
        Integer.toString(info.getPort()),
        info.getPropertyString("platform"),
        info.getType(),
        info.getPropertyString("TSN"));
  }

  public final void showHelp(View V) {
    stopQuery();
    Intent intent = new Intent(Discover.this, Help.class);
    startActivity(intent);
  }

  public final void startQuery(View v) {
    mHosts.clear();
    mHostAdapter.notifyDataSetChanged();

    // Add stored (i.e. custom) devices.
    for (Device device : db.getDevices()) {
      final HashMap<String, Object> listItem = new HashMap<String, Object>();
      listItem.put("addr", device.addr);
      listItem.put("deviceId", device.id);
      listItem.put("messageId", -1);
      listItem.put("name", device.device_name);
      listItem.put("port", device.port.toString());
      listItem.put("tsn", "-");
      listItem.put("warn_icon", android.R.drawable.ic_menu_recent_history);
      addDeviceMap(listItem);
    }
    final boolean haveStoredDevices = !mHosts.isEmpty();

    // Skip mDNS discovery on BlackBerry.
    final String osName = System.getProperty("os.name");
    Utils.log("Discover; os.name = " + osName);
    if ("qnx".equals(osName)) {
      if (mHosts.size() == 0) {
        Utils.toast(this, R.string.blackberry_discovery, Toast.LENGTH_LONG);
      }
      findViewById(R.id.refresh_button).setVisibility(View.GONE);
      return;
    }

    stopQuery();
    Utils.log("Start discovery query ...");
    mEmpty.setText("Searching ...");
    setProgressSpinner(true);

    final Discover that = this;
    Thread jmdnsThread = new Thread(new Runnable() {
      public void run() {
        // Bind mDNS to whatever interface is actually carrying traffic.  This
        // used to ask WifiManager for the address, which reports 0 on
        // anything that is not wifi -- ethernet, Chrome OS, an active VPN --
        // and left those users with no discovery at all.
        final InetAddress addr = getBindAddress();
        if (addr == null) {
          runOnUiThread(new Runnable() {
            public void run() {
              showWarning(R.string.error_get_net_addr, -1);
              setProgressSpinner(false);
            }
          });
          return;
        }
        Utils.log("Starting discovery via " + addr.getHostAddress());

        // Receiving multicast on wifi needs this lock, and the lock exists
        // only there.  On any other transport its absence is not an error, so
        // discovery goes ahead either way.
        acquireMulticastLock();

        try {
          mJmdns = JmDNS.create(addr, "localhost");
        } catch (IOException e1) {
          setProgressSpinner(false);
          if (!haveStoredDevices) {
            runOnUiThread(new Runnable() {
              public void run() {
                showWarning(R.string.error_multicast, -1);
              }
            });
          }
          return;
        }

        for (String serviceName : mServiceNames) {
          mJmdns.addServiceListener(serviceName, that);
        }
      }
    });
    jmdnsThread.start();
    try {
      jmdnsThread.join();
    } catch (InterruptedException e1) {
      Utils.logError("jmdns thread interrupted", e1);
    }

    // Test / mock data.
    /*
    checkDevice(
        "TEST Pace MG1",
        "127.0.0.2",
        "1413",
        "tcd/XG1",
        mServiceNameRpc,
        "D180509555840S2");
    checkDevice(
        "TEST Series 2",
        "127.0.0.3",
        "1413",
        "tcd/Series2",
        mServiceNameVideos,
        "6490556Q5753378");
    checkDevice(
        "TEST Virgin Media",
        "127.0.0.5",
        "1413",
        "tcd/VM",
        mServiceNameRpc,
        "B42bfedfbd02");
    checkDevice(
        "TEST No Net Control",
        "127.0.0.6",
        "1413",
        "tcd/Series4",
        mServiceNameVideos,
        "758623bea591");
    checkDevice(
        "TEST Unknown",
        "127.0.0.7",
        "1413",
        "tcd/SeriesQ",
        mServiceNameRpc,
        "QQQ129ae7882");
    /**/

    // Don't run for too long.
    new Thread(new Runnable() {
      public void run() {
        try {
          Thread.sleep(60000);
        } catch (InterruptedException e) {
          // Ignore.
        }
        stopQuery();
      }
    }).start();
  }

  protected void addDeviceMap(final HashMap<String, Object> listItem) {
    final String addr = (String) listItem.get("addr");
    final String name = (String) listItem.get("name");
    final int warnIcon = (Integer) listItem.get("warn_icon");
    final int blank = R.drawable.blank;

    Integer oldIndex = null;
    for (HashMap<String, Object> host : mHosts) {
      if (name.equals(host.get("name")) && addr.equals(host.get("addr"))) {
        if (((Integer) host.get("warn_icon") != blank && warnIcon == blank)
            || (warnIcon == android.R.drawable.ic_menu_recent_history)
        ) {
          oldIndex = mHosts.indexOf(host);
          listItem.put("deviceId", host.get("deviceId"));
          break;
        } else {
          Utils.log("Ignoring duplicate event.");
          return;
        }
      }
    }

    final Integer newIndex = oldIndex; // Final copy that the runnable can see.
    runOnUiThread(new Runnable() {
      public void run() {
        if (newIndex == null) {
          // We didn't detect an item above as an update, and we didn't short-
          // circuit to avoid duplicate events. So add a new item.
          mHosts.add(listItem);
        } else {
          mHosts.set(newIndex, listItem);
        }

        // And make it visible in the UI either way.
        mHostAdapter.notifyDataSetChanged();
      };
    });
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    MindRpc.disconnect();
    db = new Database(this);

    setTitle("TiVo Device Search");
    setContent(R.layout.list_discover);

    mEmpty = ((TextView) findViewById(android.R.id.empty));
    mHostAdapter =
        new SimpleAdapter(this, mHosts, R.layout.item_discover, new String[] {
            "name", "warn_icon" },
            new int[] { R.id.discover_name, R.id.discover_warn_icon });
    setListAdapter(mHostAdapter);

    final ListView lv = getListView();
    lv.setOnItemClickListener(this);
    lv.setOnItemLongClickListener(this);

  }

  @Override
  protected void onPause() {
    super.onPause();
    Utils.log("Activity:Pause:Discover");
    stopQuery();
  }

  @Override
  protected void onResume() {
    super.onResume();
    Utils.log("Activity:Resume:Discover");
    startQuery(null);
  }

  protected void showWarning(int messageId, final int position) {
    String message = getResources().getString(messageId);
    Utils.log("Showing warning: " + message);

    AlertDialog.Builder alert = new AlertDialog.Builder(this);
    alert.setTitle("Warning!");
    alert.setMessage(message);
    if (position >= 0 && messageIdIsMinor(messageId)) {
      alert.setCancelable(true).setNegativeButton("Cancel", null)
          .setPositiveButton("Try Anyway", new OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
              Utils.log("I foolishly clicked Try Anyway.");
              onItemClickResume(position);
            }
          });
    } else {
      alert.setCancelable(false).setPositiveButton("OK", null);
    }

    alert.create().show();
  }

  /**
   * The local address to bind mDNS to: the IPv4 address of whichever network
   * is currently active, whatever its transport.  Returns null when there is
   * no usable connection at all.
   */
  private InetAddress getBindAddress() {
    final ConnectivityManager cm = getConnectivityManager();
    if (cm != null) {
      final Network network = cm.getActiveNetwork();
      final LinkProperties props =
          network == null ? null : cm.getLinkProperties(network);
      if (props != null) {
        for (LinkAddress linkAddr : props.getLinkAddresses()) {
          final InetAddress addr = linkAddr.getAddress();
          if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
            return addr;
          }
        }
      }
    }
    // The framework can decline to describe the active network -- some VPN
    // and tethering setups do this -- so fall back to asking the interfaces
    // themselves.
    return scanInterfacesForAddress();
  }

  /** The first multicast-capable, non-loopback IPv4 address that is up. */
  private InetAddress scanInterfacesForAddress() {
    try {
      for (NetworkInterface iface :
          Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (iface.isLoopback() || !iface.isUp() || !iface.supportsMulticast()) {
          continue;
        }
        for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
          if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
            return addr;
          }
        }
      }
    } catch (SocketException e) {
      Utils.logError("Could not enumerate network interfaces", e);
    }
    return null;
  }

  /**
   * Take the wifi multicast lock, when wifi is what we are actually on.  Best
   * effort: a failure here is not fatal, because every other transport
   * receives multicast without any lock.
   */
  private void acquireMulticastLock() {
    if (!activeTransportIsWifi()) {
      return;
    }
    final WifiManager wifi = (WifiManager) getApplicationContext()
        .getSystemService(Context.WIFI_SERVICE);
    if (wifi == null) {
      return;
    }
    try {
      mMulticastLock = wifi.createMulticastLock("DVR Commander for TiVo Lock");
      mMulticastLock.setReferenceCounted(true);
      mMulticastLock.acquire();
    } catch (RuntimeException e) {
      // UnsupportedOperationException where there is no real wifi service,
      // SecurityException if the permission is somehow refused.
      Utils.logError("Could not acquire the multicast lock", e);
      mMulticastLock = null;
    }
  }

  private boolean activeTransportIsWifi() {
    final ConnectivityManager cm = getConnectivityManager();
    if (cm == null) {
      return false;
    }
    final Network network = cm.getActiveNetwork();
    final NetworkCapabilities caps =
        network == null ? null : cm.getNetworkCapabilities(network);
    return caps != null
        && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
  }

  private ConnectivityManager getConnectivityManager() {
    return (ConnectivityManager) getApplicationContext()
        .getSystemService(Context.CONNECTIVITY_SERVICE);
  }

  protected final void stopQuery() {
    runOnUiThread(new Runnable() {
      public void run() {
        setProgressSpinner(false);
        if (mEmpty != null) {
          mEmpty.setText("No results found.");
        }
      }
    });

    // JmDNS close seems to take ~6 seconds, so do that on a background thread.
    if (mJmdns != null) {
      Utils.log("Stop discovery query ...");
      final JmDNS oldMdns = mJmdns;
      mJmdns = null;
      new Thread(new Runnable() {
        public void run() {
          try {
            for (String serviceName : mServiceNames) {
              oldMdns.removeServiceListener(serviceName, Discover.this);
            }
            oldMdns.close();
          } catch (RuntimeException e) {
            Utils.logError("Could not close JmDNS!", e);
          } catch (IOException e) {
            Utils.logError("Could not close JmDNS!", e);
          }
        }
      }).start();
    }

    if (mMulticastLock != null) {
      try {
        mMulticastLock.release();
      } catch (RuntimeException e) {
        // Ignore. Likely
        // "MulticastLock under-locked DVR Commander for TiVo Lock".
      }
      mMulticastLock = null;
    }
  }

  void addDevice(
      final String name, final String addr, final String port,
      final String platform, final String type, final String tsn,
      int messageId) {
    final HashMap<String, Object> listItem = new HashMap<String, Object>();
    listItem.put("addr", addr);
    listItem.put("deviceId", null);
    listItem.put("messageId", messageId);
    listItem.put("name", name);
    listItem.put("port", port);
    listItem.put("tsn", tsn);
    listItem.put(
        "warn_icon",
        messageId == 0
            ? R.drawable.blank
            : messageIdIsMinor(messageId)
                ? android.R.drawable.ic_menu_help
                : android.R.drawable.ic_dialog_alert);
    addDeviceMap(listItem);

    Device device = db.getDeviceByTsn(tsn);
    if (device != null && device.addr != addr) {
      // We've discovered a device already listed in the DB, and its address
      // has changed (DHCP!!! *shake fist*).  Update the DB so that future
      // connections work as expected without forcing discovery.
      device.addr = addr;
      db.saveDevice(device);
    }
  }

  void checkDevice(
      final String name, final String addr, final String port,
      final String platform, final String type, final String tsn) {
    int messageId = 0;

    SharedPreferences prefs
        = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

    // A model the table does not name is not the same as one it rejects: an
    // unrecognised TSN is most likely a box newer than the table, so it stays
    // on the softer "unknown" warning that offers Try Anyway.
    final TivoModel model = TivoModel.forTsn(tsn);
    if (model != null && model.supported) {
      if (!mServiceNameRpc.equals(type)) {
        messageId = R.string.error_net_control;
      }
    } else if (prefs.getBoolean("skip_compat", false)) {
      messageId = R.string.device_compat_skip;
    } else if (model != null) {
      messageId = R.string.device_unsupported;
    } else {
      messageId = R.string.device_unknown;
    }

    addDevice(name, addr, port, platform, type, tsn, messageId);
  }

  protected void setProgressSpinner(boolean running) {
    Utils.showProgress(this, running);
    View refreshButton = findViewById(R.id.refresh_button);
    if (refreshButton != null) {
      refreshButton.setEnabled(!running);
    }
  }

  private boolean messageIdIsMinor(int messageId) {
    return (messageId == R.string.device_unknown
        || messageId == R.string.device_compat_skip);
  }
}
