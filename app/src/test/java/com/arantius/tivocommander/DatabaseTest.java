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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** The device store behind Discover and the connection flow. */
@RunWith(RobolectricTestRunner.class)
public class DatabaseTest {
  private Context mContext;
  private Database mDb;

  private static Device device(String name, String addr, String tsn) {
    Device device = new Device();
    device.device_name = name;
    device.addr = addr;
    device.tsn = tsn;
    device.mak = "0000000000";
    device.port = 1413;
    return device;
  }

  @Before
  public void setUp() {
    mContext = RuntimeEnvironment.getApplication();
    mDb = new Database(mContext);
  }

  @Test
  public void savingADeviceGivesItAnId() {
    Device device = device("Living Room", "192.0.2.10", "tsn:1");
    assertNull(device.id);
    mDb.saveDevice(device);
    assertNotNull("saveDevice should fill the id in", device.id);
  }

  @Test
  public void aSavedDeviceComesBackWholeById() {
    Device saved = device("Living Room", "192.0.2.10", "tsn:1");
    mDb.saveDevice(saved);

    Device read = mDb.getDevice(saved.id);
    assertNotNull(read);
    assertEquals("Living Room", read.device_name);
    assertEquals("192.0.2.10", read.addr);
    assertEquals("tsn:1", read.tsn);
    assertEquals("0000000000", read.mak);
    assertEquals(Integer.valueOf(1413), read.port);
  }

  @Test
  public void devicesAreFoundByTsnAndByNameAndAddress() {
    mDb.saveDevice(device("Living Room", "192.0.2.10", "tsn:1"));
    mDb.saveDevice(device("Bedroom", "192.0.2.11", "tsn:2"));

    assertEquals("Bedroom", mDb.getDeviceByTsn("tsn:2").device_name);
    assertEquals("tsn:1",
        mDb.getNamedDevice("Living Room", "192.0.2.10").tsn);
  }

  @Test
  public void lookingUpSomethingThatIsNotThereGivesNull() {
    assertNull(mDb.getDevice(4242L));
    assertNull(mDb.getDeviceByTsn("tsn:nope"));
    assertNull(mDb.getNamedDevice("Nowhere", "192.0.2.99"));
    assertNull("no devices yet", mDb.getLastUsedDevice());
  }

  @Test
  public void getDevicesListsThemByName() {
    mDb.saveDevice(device("Zed", "192.0.2.12", "tsn:3"));
    mDb.saveDevice(device("Alpha", "192.0.2.10", "tsn:1"));
    mDb.saveDevice(device("Middle", "192.0.2.11", "tsn:2"));

    ArrayList<Device> devices = mDb.getDevices();
    assertEquals(3, devices.size());
    assertEquals("Alpha", devices.get(0).device_name);
    assertEquals("Middle", devices.get(1).device_name);
    assertEquals("Zed", devices.get(2).device_name);
  }

  @Test
  public void switchingDevicesDecidesWhichOneConnectsNextTime() {
    // MindRpc.checkSettings() opens whatever getLastUsedDevice() answers, so
    // this is what picking a device in Discover has to change.
    Device first = device("Living Room", "192.0.2.10", "tsn:1");
    Device second = device("Bedroom", "192.0.2.11", "tsn:2");
    mDb.saveDevice(first);
    mDb.saveDevice(second);

    mDb.switchDevice(first);
    assertEquals("tsn:1", mDb.getLastUsedDevice().tsn);

    mDb.switchDevice(second);
    assertEquals("tsn:2", mDb.getLastUsedDevice().tsn);
  }

  @Test
  public void aDeviceNeverSwitchedToIsNotTheLastUsedOne() {
    // saveDevice() does not set used_time, so a device that has only been
    // added ranks below any that has been connected to.
    Device used = device("Living Room", "192.0.2.10", "tsn:1");
    mDb.saveDevice(used);
    mDb.switchDevice(used);
    mDb.saveDevice(device("Just Added", "192.0.2.11", "tsn:2"));

    assertEquals("tsn:1", mDb.getLastUsedDevice().tsn);
  }

  @Test
  public void savingADeviceThatHasAnIdUpdatesItInPlace() {
    Device device = device("Living Room", "192.0.2.10", "tsn:1");
    mDb.saveDevice(device);
    Long id = device.id;

    device.mak = "1111111111";
    device.device_name = "Renamed";
    mDb.saveDevice(device);

    assertEquals("should not have inserted a second row", 1,
        mDb.getDevices().size());
    assertEquals(id, device.id);
    assertEquals("1111111111", mDb.getDevice(id).mak);
    assertEquals("Renamed", mDb.getDevice(id).device_name);
  }

  @Test
  public void deletingADeviceRemovesOnlyThatOne() {
    Device first = device("Living Room", "192.0.2.10", "tsn:1");
    Device second = device("Bedroom", "192.0.2.11", "tsn:2");
    mDb.saveDevice(first);
    mDb.saveDevice(second);

    mDb.deleteDevice(first.id);

    assertNull(mDb.getDevice(first.id));
    assertNotNull(mDb.getDevice(second.id));
  }

  @Test
  public void legacyPreferencesAreMigratedIntoADeviceRow() {
    // How a pre-Discover install, which kept one address in preferences,
    // becomes a device the connection flow can use.
    SharedPreferences.Editor prefs =
        PreferenceManager.getDefaultSharedPreferences(mContext).edit();
    prefs.putString("tivo_addr", "192.0.2.50");
    prefs.putString("tivo_port", "1413");
    prefs.putString("tivo_mak", "1234567890");
    prefs.commit();

    mDb.portLegacySettings(mContext);

    ArrayList<Device> devices = mDb.getDevices();
    assertEquals(1, devices.size());
    assertEquals("192.0.2.50", devices.get(0).addr);
    assertEquals("1234567890", devices.get(0).mak);
    assertEquals(Integer.valueOf(1413), devices.get(0).port);
  }

  @Test
  public void migrationDoesNothingWithoutLegacyPreferences() {
    mDb.portLegacySettings(mContext);
    assertTrue(mDb.getDevices().isEmpty());
  }
}
