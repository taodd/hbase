/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.backup.impl;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.backup.BackupInfo;
import org.apache.hadoop.hbase.backup.BackupInfo.BackupState;
import org.apache.hadoop.hbase.backup.BackupRestoreConstants;
import org.apache.hadoop.hbase.backup.util.BackupUtils;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.shaded.protobuf.generated.BackupProtos;
import org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;

/**
 * This class provides API to access backup system table<br>
 *
 * Backup system table schema:<br>
 * <p><ul>
 * <li>1. Backup sessions rowkey= "session:"+backupId; value =serialized BackupInfo</li>
 * <li>2. Backup start code rowkey = "startcode:"+backupRoot; value = startcode</li>
 * <li>3. Incremental backup set rowkey="incrbackupset:"+backupRoot; value=[list of tables]</li>
 * <li>4. Table-RS-timestamp map rowkey="trslm:"+backupRoot+table_name;
 * value = map[RS-> last WAL timestamp]</li>
 * <li>5. RS - WAL ts map rowkey="rslogts:"+backupRoot +server; value = last WAL timestamp</li>
 * <li>6. WALs recorded rowkey="wals:"+WAL unique file name;
 * value = backupId and full WAL file name</li>
 * </ul></p>
 */
@InterfaceAudience.Private
public final class BackupSystemTable implements Closeable {

  static class WALItem {
    String backupId;
    String walFile;
    String backupRoot;

    WALItem(String backupId, String walFile, String backupRoot) {
      this.backupId = backupId;
      this.walFile = walFile;
      this.backupRoot = backupRoot;
    }

    public String getBackupId() {
      return backupId;
    }

    public String getWalFile() {
      return walFile;
    }

    public String getBackupRoot() {
      return backupRoot;
    }

    @Override
    public String toString() {
      return Path.SEPARATOR + backupRoot + Path.SEPARATOR + backupId + Path.SEPARATOR + walFile;
    }

  }

  private static final Log LOG = LogFactory.getLog(BackupSystemTable.class);

  private TableName tableName;
  /**
   *  Stores backup sessions (contexts)
   */
  final static byte[] SESSIONS_FAMILY = "session".getBytes();
  /**
   * Stores other meta
   */
  final static byte[] META_FAMILY = "meta".getBytes();
  /**
   *  Connection to HBase cluster, shared among all instances
   */
  private final Connection connection;


  private final static String BACKUP_INFO_PREFIX = "session:";
  private final static String START_CODE_ROW = "startcode:";
  private final static String INCR_BACKUP_SET = "incrbackupset:";
  private final static String TABLE_RS_LOG_MAP_PREFIX = "trslm:";
  private final static String RS_LOG_TS_PREFIX = "rslogts:";
  private final static String WALS_PREFIX = "wals:";
  private final static String SET_KEY_PREFIX = "backupset:";

  private final static byte[] EMPTY_VALUE = new byte[] {};

  // Safe delimiter in a string
  private final static String NULL = "\u0000";

  public BackupSystemTable(Connection conn) throws IOException {
    this.connection = conn;
    tableName = BackupSystemTable.getTableName(conn.getConfiguration());
    checkSystemTable();
  }

  private void checkSystemTable() throws IOException {
    try (Admin admin = connection.getAdmin();) {

      if (!admin.tableExists(tableName)) {
        HTableDescriptor backupHTD =
            BackupSystemTable.getSystemTableDescriptor(connection.getConfiguration());
        admin.createTable(backupHTD);
      }
      waitForSystemTable(admin);
    }
  }

  private void waitForSystemTable(Admin admin) throws IOException {
    long TIMEOUT = 60000;
    long startTime = EnvironmentEdgeManager.currentTime();
    while (!admin.tableExists(tableName) || !admin.isTableAvailable(tableName)) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
      }
      if (EnvironmentEdgeManager.currentTime() - startTime > TIMEOUT) {
        throw new IOException("Failed to create backup system table after "+ TIMEOUT+"ms");
      }
    }
    LOG.debug("Backup table exists and available");

  }



  @Override
  public void close() {
    // do nothing
  }

  /**
   * Updates status (state) of a backup session in backup system table table
   * @param info backup info
   * @throws IOException exception
   */
  public void updateBackupInfo(BackupInfo info) throws IOException {

    if (LOG.isTraceEnabled()) {
      LOG.trace("update backup status in backup system table for: " + info.getBackupId()
          + " set status=" + info.getState());
    }
    try (Table table = connection.getTable(tableName)) {
      Put put = createPutForBackupInfo(info);
      table.put(put);
    }
  }

  /**
   * Deletes backup status from backup system table table
   * @param backupId backup id
   * @throws IOException exception
   */

  public void deleteBackupInfo(String backupId) throws IOException {

    if (LOG.isTraceEnabled()) {
      LOG.trace("delete backup status in backup system table for " + backupId);
    }
    try (Table table = connection.getTable(tableName)) {
      Delete del = createDeleteForBackupInfo(backupId);
      table.delete(del);
    }
  }

  /**
   * Reads backup status object (instance of backup info) from backup system table table
   * @param backupId backup id
   * @return Current status of backup session or null
   */

  public BackupInfo readBackupInfo(String backupId) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("read backup status from backup system table for: " + backupId);
    }

    try (Table table = connection.getTable(tableName)) {
      Get get = createGetForBackupInfo(backupId);
      Result res = table.get(get);
      if (res.isEmpty()) {
        return null;
      }
      return resultToBackupInfo(res);
    }
  }

  /**
   * Read the last backup start code (timestamp) of last successful backup. Will return null if
   * there is no start code stored on hbase or the value is of length 0. These two cases indicate
   * there is no successful backup completed so far.
   * @param backupRoot directory path to backup destination
   * @return the timestamp of last successful backup
   * @throws IOException exception
   */
  public String readBackupStartCode(String backupRoot) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("read backup start code from backup system table");
    }
    try (Table table = connection.getTable(tableName)) {
      Get get = createGetForStartCode(backupRoot);
      Result res = table.get(get);
      if (res.isEmpty()) {
        return null;
      }
      Cell cell = res.listCells().get(0);
      byte[] val = CellUtil.cloneValue(cell);
      if (val.length == 0) {
        return null;
      }
      return new String(val);
    }
  }

  /**
   * Write the start code (timestamp) to backup system table. If passed in null, then write 0 byte.
   * @param startCode start code
   * @param backupRoot root directory path to backup
   * @throws IOException exception
   */
  public void writeBackupStartCode(Long startCode, String backupRoot) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("write backup start code to backup system table " + startCode);
    }
    try (Table table = connection.getTable(tableName)) {
      Put put = createPutForStartCode(startCode.toString(), backupRoot);
      table.put(put);
    }
  }

  /**
   * Get the Region Servers log information after the last log roll from backup system table.
   * @param backupRoot root directory path to backup
   * @return RS log info
   * @throws IOException exception
   */
  public HashMap<String, Long> readRegionServerLastLogRollResult(String backupRoot)
      throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("read region server last roll log result to backup system table");
    }

    Scan scan = createScanForReadRegionServerLastLogRollResult(backupRoot);

    try (Table table = connection.getTable(tableName);
        ResultScanner scanner = table.getScanner(scan)) {
      Result res = null;
      HashMap<String, Long> rsTimestampMap = new HashMap<String, Long>();
      while ((res = scanner.next()) != null) {
        res.advance();
        Cell cell = res.current();
        byte[] row = CellUtil.cloneRow(cell);
        String server =
            getServerNameForReadRegionServerLastLogRollResult(row);
        byte[] data = CellUtil.cloneValue(cell);
        rsTimestampMap.put(server, Bytes.toLong(data));
      }
      return rsTimestampMap;
    }
  }

  /**
   * Writes Region Server last roll log result (timestamp) to backup system table table
   * @param server Region Server name
   * @param ts last log timestamp
   * @param backupRoot root directory path to backup
   * @throws IOException exception
   */
  public void writeRegionServerLastLogRollResult(String server, Long ts, String backupRoot)
      throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("write region server last roll log result to backup system table");
    }
    try (Table table = connection.getTable(tableName)) {
      Put put =
          createPutForRegionServerLastLogRollResult(server, ts, backupRoot);
      table.put(put);
    }
  }

  /**
   * Get all completed backup information (in desc order by time)
   * @param onlyCompleted true, if only successfully completed sessions
   * @return history info of BackupCompleteData
   * @throws IOException exception
   */
  public ArrayList<BackupInfo> getBackupHistory(boolean onlyCompleted) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("get backup history from backup system table");
    }
    ArrayList<BackupInfo> list;
    BackupState state = onlyCompleted ? BackupState.COMPLETE : BackupState.ANY;
    list = getBackupInfos(state);
    return BackupUtils.sortHistoryListDesc(list);
  }

  /**
   * Get all backups history
   * @return list of backup info
   * @throws IOException
   */
  public List<BackupInfo> getBackupHistory() throws IOException {
    return getBackupHistory(false);
  }

  /**
   * Get first n backup history records
   * @param n number of records
   * @return list of records
   * @throws IOException
   */
  public List<BackupInfo> getHistory(int n) throws IOException {

    List<BackupInfo> history = getBackupHistory();
    if (history.size() <= n) return history;
    List<BackupInfo> list = new ArrayList<BackupInfo>();
    for (int i = 0; i < n; i++) {
      list.add(history.get(i));
    }
    return list;

  }

  /**
   * Get backup history records filtered by list of filters.
   * @param n max number of records
   * @param filters list of filters
   * @return backup records
   * @throws IOException
   */
  public List<BackupInfo> getBackupHistory(int n, BackupInfo.Filter... filters) throws IOException {
    if (filters.length == 0) return getHistory(n);

    List<BackupInfo> history = getBackupHistory();
    List<BackupInfo> result = new ArrayList<BackupInfo>();
    for (BackupInfo bi : history) {
      if (result.size() == n) break;
      boolean passed = true;
      for (int i = 0; i < filters.length; i++) {
        if (!filters[i].apply(bi)) {
          passed = false;
          break;
        }
      }
      if (passed) {
        result.add(bi);
      }
    }
    return result;

  }

  /**
   * Get history for backup destination
   * @param backupRoot backup destination path
   * @return List of backup info
   * @throws IOException
   */
  public List<BackupInfo> getBackupHistory(String backupRoot) throws IOException {
    ArrayList<BackupInfo> history = getBackupHistory(false);
    for (Iterator<BackupInfo> iterator = history.iterator(); iterator.hasNext();) {
      BackupInfo info = iterator.next();
      if (!backupRoot.equals(info.getBackupRootDir())) {
        iterator.remove();
      }
    }
    return history;
  }

  /**
   * Get history for a table
   * @param name table name
   * @return history for a table
   * @throws IOException
   */
  public List<BackupInfo> getBackupHistoryForTable(TableName name) throws IOException {
    List<BackupInfo> history = getBackupHistory();
    List<BackupInfo> tableHistory = new ArrayList<BackupInfo>();
    for (BackupInfo info : history) {
      List<TableName> tables = info.getTableNames();
      if (tables.contains(name)) {
        tableHistory.add(info);
      }
    }
    return tableHistory;
  }

  public Map<TableName, ArrayList<BackupInfo>> getBackupHistoryForTableSet(Set<TableName> set,
      String backupRoot) throws IOException {
    List<BackupInfo> history = getBackupHistory(backupRoot);
    Map<TableName, ArrayList<BackupInfo>> tableHistoryMap =
        new HashMap<TableName, ArrayList<BackupInfo>>();
    for (Iterator<BackupInfo> iterator = history.iterator(); iterator.hasNext();) {
      BackupInfo info = iterator.next();
      if (!backupRoot.equals(info.getBackupRootDir())) {
        continue;
      }
      List<TableName> tables = info.getTableNames();
      for (TableName tableName : tables) {
        if (set.contains(tableName)) {
          ArrayList<BackupInfo> list = tableHistoryMap.get(tableName);
          if (list == null) {
            list = new ArrayList<BackupInfo>();
            tableHistoryMap.put(tableName, list);
          }
          list.add(info);
        }
      }
    }
    return tableHistoryMap;
  }

  /**
   * Get all backup sessions with a given state (in descending order by time)
   * @param state backup session state
   * @return history info of backup info objects
   * @throws IOException exception
   */
  public ArrayList<BackupInfo> getBackupInfos(BackupState state) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("get backup infos from backup system table");
    }

    Scan scan = createScanForBackupHistory();
    ArrayList<BackupInfo> list = new ArrayList<BackupInfo>();

    try (Table table = connection.getTable(tableName);
        ResultScanner scanner = table.getScanner(scan)) {
      Result res = null;
      while ((res = scanner.next()) != null) {
        res.advance();
        BackupInfo context = cellToBackupInfo(res.current());
        if (state != BackupState.ANY && context.getState() != state) {
          continue;
        }
        list.add(context);
      }
      return list;
    }
  }

  /**
   * Write the current timestamps for each regionserver to backup system table after a successful
   * full or incremental backup. The saved timestamp is of the last log file that was backed up
   * already.
   * @param tables tables
   * @param newTimestamps timestamps
   * @param backupRoot root directory path to backup
   * @throws IOException exception
   */
  public void writeRegionServerLogTimestamp(Set<TableName> tables,
      HashMap<String, Long> newTimestamps, String backupRoot) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("write RS log time stamps to backup system table for tables ["
          + StringUtils.join(tables, ",") + "]");
    }
    List<Put> puts = new ArrayList<Put>();
    for (TableName table : tables) {
      byte[] smapData = toTableServerTimestampProto(table, newTimestamps).toByteArray();
      Put put =
          createPutForWriteRegionServerLogTimestamp(table, smapData,
            backupRoot);
      puts.add(put);
    }
    try (Table table = connection.getTable(tableName)) {
      table.put(puts);
    }
  }

  /**
   * Read the timestamp for each region server log after the last successful backup. Each table has
   * its own set of the timestamps. The info is stored for each table as a concatenated string of
   * rs->timestapmp
   * @param backupRoot root directory path to backup
   * @return the timestamp for each region server. key: tableName value:
   *         RegionServer,PreviousTimeStamp
   * @throws IOException exception
   */
  public HashMap<TableName, HashMap<String, Long>> readLogTimestampMap(String backupRoot)
      throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("read RS log ts from backup system table for root=" + backupRoot);
    }

    HashMap<TableName, HashMap<String, Long>> tableTimestampMap =
        new HashMap<TableName, HashMap<String, Long>>();

    Scan scan = createScanForReadLogTimestampMap(backupRoot);
    try (Table table = connection.getTable(tableName);
        ResultScanner scanner = table.getScanner(scan)) {
      Result res = null;
      while ((res = scanner.next()) != null) {
        res.advance();
        Cell cell = res.current();
        byte[] row = CellUtil.cloneRow(cell);
        String tabName = getTableNameForReadLogTimestampMap(row);
        TableName tn = TableName.valueOf(tabName);
        byte[] data = CellUtil.cloneValue(cell);
        if (data == null) {
          throw new IOException("Data of last backup data from backup system table "
              + "is empty. Create a backup first.");
        }
        if (data != null && data.length > 0) {
          HashMap<String, Long> lastBackup =
              fromTableServerTimestampProto(BackupProtos.TableServerTimestamp.parseFrom(data));
          tableTimestampMap.put(tn, lastBackup);
        }
      }
      return tableTimestampMap;
    }
  }

  private BackupProtos.TableServerTimestamp toTableServerTimestampProto(TableName table,
      Map<String, Long> map) {
    BackupProtos.TableServerTimestamp.Builder tstBuilder =
        BackupProtos.TableServerTimestamp.newBuilder();
    tstBuilder.setTableName(org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil
        .toProtoTableName(table));

    for (Entry<String, Long> entry : map.entrySet()) {
      BackupProtos.ServerTimestamp.Builder builder = BackupProtos.ServerTimestamp.newBuilder();
      HBaseProtos.ServerName.Builder snBuilder = HBaseProtos.ServerName.newBuilder();
      ServerName sn = ServerName.parseServerName(entry.getKey());
      snBuilder.setHostName(sn.getHostname());
      snBuilder.setPort(sn.getPort());
      builder.setServerName(snBuilder.build());
      builder.setTimestamp(entry.getValue());
      tstBuilder.addServerTimestamp(builder.build());
    }

    return tstBuilder.build();
  }

  private HashMap<String, Long> fromTableServerTimestampProto(
      BackupProtos.TableServerTimestamp proto) {
    HashMap<String, Long> map = new HashMap<String, Long>();
    List<BackupProtos.ServerTimestamp> list = proto.getServerTimestampList();
    for (BackupProtos.ServerTimestamp st : list) {
      ServerName sn =
          org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil.toServerName(st.getServerName());
      map.put(sn.getHostname() + ":" + sn.getPort(), st.getTimestamp());
    }
    return map;
  }

  /**
   * Return the current tables covered by incremental backup.
   * @param backupRoot root directory path to backup
   * @return set of tableNames
   * @throws IOException exception
   */
  public Set<TableName> getIncrementalBackupTableSet(String backupRoot) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("get incremental backup table set from backup system table");
    }
    TreeSet<TableName> set = new TreeSet<>();

    try (Table table = connection.getTable(tableName)) {
      Get get = createGetForIncrBackupTableSet(backupRoot);
      Result res = table.get(get);
      if (res.isEmpty()) {
        return set;
      }
      List<Cell> cells = res.listCells();
      for (Cell cell : cells) {
        // qualifier = table name - we use table names as qualifiers
        set.add(TableName.valueOf(CellUtil.cloneQualifier(cell)));
      }
      return set;
    }
  }

  /**
   * Add tables to global incremental backup set
   * @param tables set of tables
   * @param backupRoot root directory path to backup
   * @throws IOException exception
   */
  public void addIncrementalBackupTableSet(Set<TableName> tables, String backupRoot)
      throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("Add incremental backup table set to backup system table. ROOT=" + backupRoot
          + " tables [" + StringUtils.join(tables, " ") + "]");
      for (TableName table : tables) {
        LOG.debug(table);
      }
    }
    try (Table table = connection.getTable(tableName)) {
      Put put = createPutForIncrBackupTableSet(tables, backupRoot);
      table.put(put);
    }
  }

  /**
   * Deletes incremental backup set for a backup destination
   * @param backupRoot backup root
   */

  public void deleteIncrementalBackupTableSet(String backupRoot) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("Delete incremental backup table set to backup system table. ROOT=" + backupRoot);
    }
    try (Table table = connection.getTable(tableName)) {
      Delete delete = createDeleteForIncrBackupTableSet(backupRoot);
      table.delete(delete);
    }
  }

  /**
   * Register WAL files as eligible for deletion
   * @param files files
   * @param backupId backup id
   * @param backupRoot root directory path to backup destination
   * @throws IOException exception
   */
  public void addWALFiles(List<String> files, String backupId, String backupRoot)
      throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("add WAL files to backup system table: " + backupId + " " + backupRoot + " files ["
          + StringUtils.join(files, ",") + "]");
      for (String f : files) {
        LOG.debug("add :" + f);
      }
    }
    try (Table table = connection.getTable(tableName)) {
      List<Put> puts =
          createPutsForAddWALFiles(files, backupId, backupRoot);
      table.put(puts);
    }
  }

  /**
   * Register WAL files as eligible for deletion
   * @param backupRoot root directory path to backup
   * @throws IOException exception
   */
  public Iterator<WALItem> getWALFilesIterator(String backupRoot) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("get WAL files from backup system table");
    }
    final Table table = connection.getTable(tableName);
    Scan scan = createScanForGetWALs(backupRoot);
    final ResultScanner scanner = table.getScanner(scan);
    final Iterator<Result> it = scanner.iterator();
    return new Iterator<WALItem>() {

      @Override
      public boolean hasNext() {
        boolean next = it.hasNext();
        if (!next) {
          // close all
          try {
            scanner.close();
            table.close();
          } catch (IOException e) {
            LOG.error("Close WAL Iterator", e);
          }
        }
        return next;
      }

      @Override
      public WALItem next() {
        Result next = it.next();
        List<Cell> cells = next.listCells();
        byte[] buf = cells.get(0).getValueArray();
        int len = cells.get(0).getValueLength();
        int offset = cells.get(0).getValueOffset();
        String backupId = new String(buf, offset, len);
        buf = cells.get(1).getValueArray();
        len = cells.get(1).getValueLength();
        offset = cells.get(1).getValueOffset();
        String walFile = new String(buf, offset, len);
        buf = cells.get(2).getValueArray();
        len = cells.get(2).getValueLength();
        offset = cells.get(2).getValueOffset();
        String backupRoot = new String(buf, offset, len);
        return new WALItem(backupId, walFile, backupRoot);
      }

      @Override
      public void remove() {
        // not implemented
        throw new RuntimeException("remove is not supported");
      }
    };

  }

  /**
   * Check if WAL file is eligible for deletion Future: to support all backup destinations
   * @param file name of a file to check
   * @return true, if deletable, false otherwise.
   * @throws IOException exception
   */
  public boolean isWALFileDeletable(String file) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("Check if WAL file has been already backed up in backup system table " + file);
    }
    try (Table table = connection.getTable(tableName)) {
      Get get = createGetForCheckWALFile(file);
      Result res = table.get(get);
      if (res.isEmpty()) {
        return false;
      }
      return true;
    }
  }

  /**
   * Checks if we have at least one backup session in backup system table This API is used by
   * BackupLogCleaner
   * @return true, if - at least one session exists in backup system table table
   * @throws IOException exception
   */
  public boolean hasBackupSessions() throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("Has backup sessions from backup system table");
    }
    boolean result = false;
    Scan scan = createScanForBackupHistory();
    scan.setCaching(1);
    try (Table table = connection.getTable(tableName);
        ResultScanner scanner = table.getScanner(scan)) {
      if (scanner.next() != null) {
        result = true;
      }
      return result;
    }
  }

  /**
   * BACKUP SETS
   */

  /**
   * Get backup set list
   * @return backup set list
   * @throws IOException
   */
  public List<String> listBackupSets() throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace(" Backup set list");
    }
    List<String> list = new ArrayList<String>();
    Table table = null;
    ResultScanner scanner = null;
    try {
      table = connection.getTable(tableName);
      Scan scan = createScanForBackupSetList();
      scan.setMaxVersions(1);
      scanner = table.getScanner(scan);
      Result res = null;
      while ((res = scanner.next()) != null) {
        res.advance();
        list.add(cellKeyToBackupSetName(res.current()));
      }
      return list;
    } finally {
      if (scanner != null) {
        scanner.close();
      }
      if (table != null) {
        table.close();
      }
    }
  }

  /**
   * Get backup set description (list of tables)
   * @param name set's name
   * @return list of tables in a backup set
   * @throws IOException
   */
  public List<TableName> describeBackupSet(String name) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace(" Backup set describe: " + name);
    }
    Table table = null;
    try {
      table = connection.getTable(tableName);
      Get get = createGetForBackupSet(name);
      Result res = table.get(get);
      if (res.isEmpty()) return null;
      res.advance();
      String[] tables = cellValueToBackupSet(res.current());
      return toList(tables);
    } finally {
      if (table != null) {
        table.close();
      }
    }
  }

  private List<TableName> toList(String[] tables) {
    List<TableName> list = new ArrayList<TableName>(tables.length);
    for (String name : tables) {
      list.add(TableName.valueOf(name));
    }
    return list;
  }

  /**
   * Add backup set (list of tables)
   * @param name set name
   * @param newTables list of tables, comma-separated
   * @throws IOException
   */
  public void addToBackupSet(String name, String[] newTables) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("Backup set add: " + name + " tables [" + StringUtils.join(newTables, " ") + "]");
    }
    Table table = null;
    String[] union = null;
    try {
      table = connection.getTable(tableName);
      Get get = createGetForBackupSet(name);
      Result res = table.get(get);
      if (res.isEmpty()) {
        union = newTables;
      } else {
        res.advance();
        String[] tables = cellValueToBackupSet(res.current());
        union = merge(tables, newTables);
      }
      Put put = createPutForBackupSet(name, union);
      table.put(put);
    } finally {
      if (table != null) {
        table.close();
      }
    }
  }

  private String[] merge(String[] tables, String[] newTables) {
    List<String> list = new ArrayList<String>();
    // Add all from tables
    for (String t : tables) {
      list.add(t);
    }
    for (String nt : newTables) {
      if (list.contains(nt)) continue;
      list.add(nt);
    }
    String[] arr = new String[list.size()];
    list.toArray(arr);
    return arr;
  }

  /**
   * Remove tables from backup set (list of tables)
   * @param name set name
   * @param toRemove list of tables
   * @throws IOException
   */
  public void removeFromBackupSet(String name, String[] toRemove) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace(" Backup set remove from : " + name + " tables [" + StringUtils.join(toRemove, " ")
          + "]");
    }
    Table table = null;
    String[] disjoint = null;
    String[] tables = null;
    try {
      table = connection.getTable(tableName);
      Get get = createGetForBackupSet(name);
      Result res = table.get(get);
      if (res.isEmpty()) {
        LOG.warn("Backup set '" + name + "' not found.");
        return;
      } else {
        res.advance();
        tables = cellValueToBackupSet(res.current());
        disjoint = disjoin(tables, toRemove);
      }
      if (disjoint.length > 0 && disjoint.length != tables.length) {
        Put put = createPutForBackupSet(name, disjoint);
        table.put(put);
      } else if(disjoint.length == tables.length) {
        LOG.warn("Backup set '" + name + "' does not contain tables ["
            + StringUtils.join(toRemove, " ") + "]");
      } else { // disjoint.length == 0 and tables.length >0
        // Delete  backup set
        LOG.info("Backup set '"+name+"' is empty. Deleting.");
        deleteBackupSet(name);
      }
    } finally {
      if (table != null) {
        table.close();
      }
    }
  }

  private String[] disjoin(String[] tables, String[] toRemove) {
    List<String> list = new ArrayList<String>();
    // Add all from tables
    for (String t : tables) {
      list.add(t);
    }
    for (String nt : toRemove) {
      if (list.contains(nt)) {
        list.remove(nt);
      }
    }
    String[] arr = new String[list.size()];
    list.toArray(arr);
    return arr;
  }

  /**
   * Delete backup set
   * @param name set's name
   * @throws IOException
   */
  public void deleteBackupSet(String name) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace(" Backup set delete: " + name);
    }
    Table table = null;
    try {
      table = connection.getTable(tableName);
      Delete del = createDeleteForBackupSet(name);
      table.delete(del);
    } finally {
      if (table != null) {
        table.close();
      }
    }
  }

  /**
   * Get backup system table descriptor
   * @return table's descriptor
   */
  public static HTableDescriptor getSystemTableDescriptor(Configuration conf) {

    HTableDescriptor tableDesc = new HTableDescriptor(getTableName(conf));
    HColumnDescriptor colSessionsDesc = new HColumnDescriptor(SESSIONS_FAMILY);
    colSessionsDesc.setMaxVersions(1);
    // Time to keep backup sessions (secs)
    Configuration config = HBaseConfiguration.create();
    int ttl =
        config.getInt(BackupRestoreConstants.BACKUP_SYSTEM_TTL_KEY,
          BackupRestoreConstants.BACKUP_SYSTEM_TTL_DEFAULT);
    colSessionsDesc.setTimeToLive(ttl);
    tableDesc.addFamily(colSessionsDesc);
    HColumnDescriptor colMetaDesc = new HColumnDescriptor(META_FAMILY);
    tableDesc.addFamily(colMetaDesc);
    return tableDesc;
  }

  public static TableName getTableName(Configuration conf) {
    String name =
        conf.get(BackupRestoreConstants.BACKUP_SYSTEM_TABLE_NAME_KEY,
          BackupRestoreConstants.BACKUP_SYSTEM_TABLE_NAME_DEFAULT);
    return TableName.valueOf(name);
  }

  public static String getTableNameAsString(Configuration conf) {
    return getTableName(conf).getNameAsString();
  }





  /**
   * Creates Put operation for a given backup info object
   * @param context backup info
   * @return put operation
   * @throws IOException exception
   */
  private Put createPutForBackupInfo(BackupInfo context) throws IOException {
    Put put = new Put(rowkey(BACKUP_INFO_PREFIX, context.getBackupId()));
    put.addColumn(BackupSystemTable.SESSIONS_FAMILY, Bytes.toBytes("context"),
      context.toByteArray());
    return put;
  }

  /**
   * Creates Get operation for a given backup id
   * @param backupId backup's ID
   * @return get operation
   * @throws IOException exception
   */
  private Get createGetForBackupInfo(String backupId) throws IOException {
    Get get = new Get(rowkey(BACKUP_INFO_PREFIX, backupId));
    get.addFamily(BackupSystemTable.SESSIONS_FAMILY);
    get.setMaxVersions(1);
    return get;
  }

  /**
   * Creates Delete operation for a given backup id
   * @param backupId backup's ID
   * @return delete operation
   * @throws IOException exception
   */
  private Delete createDeleteForBackupInfo(String backupId) {
    Delete del = new Delete(rowkey(BACKUP_INFO_PREFIX, backupId));
    del.addFamily(BackupSystemTable.SESSIONS_FAMILY);
    return del;
  }

  /**
   * Converts Result to BackupInfo
   * @param res HBase result
   * @return backup info instance
   * @throws IOException exception
   */
  private BackupInfo resultToBackupInfo(Result res) throws IOException {
    res.advance();
    Cell cell = res.current();
    return cellToBackupInfo(cell);
  }

  /**
   * Creates Get operation to retrieve start code from backup system table
   * @return get operation
   * @throws IOException exception
   */
  private Get createGetForStartCode(String rootPath) throws IOException {
    Get get = new Get(rowkey(START_CODE_ROW, rootPath));
    get.addFamily(BackupSystemTable.META_FAMILY);
    get.setMaxVersions(1);
    return get;
  }

  /**
   * Creates Put operation to store start code to backup system table
   * @return put operation
   * @throws IOException exception
   */
  private Put createPutForStartCode(String startCode, String rootPath) {
    Put put = new Put(rowkey(START_CODE_ROW, rootPath));
    put.addColumn(BackupSystemTable.META_FAMILY, Bytes.toBytes("startcode"),
      Bytes.toBytes(startCode));
    return put;
  }

  /**
   * Creates Get to retrieve incremental backup table set from backup system table
   * @return get operation
   * @throws IOException exception
   */
  private Get createGetForIncrBackupTableSet(String backupRoot) throws IOException {
    Get get = new Get(rowkey(INCR_BACKUP_SET, backupRoot));
    get.addFamily(BackupSystemTable.META_FAMILY);
    get.setMaxVersions(1);
    return get;
  }

  /**
   * Creates Put to store incremental backup table set
   * @param tables tables
   * @return put operation
   */
  private Put createPutForIncrBackupTableSet(Set<TableName> tables, String backupRoot) {
    Put put = new Put(rowkey(INCR_BACKUP_SET, backupRoot));
    for (TableName table : tables) {
      put.addColumn(BackupSystemTable.META_FAMILY, Bytes.toBytes(table.getNameAsString()),
        EMPTY_VALUE);
    }
    return put;
  }

  /**
   * Creates Delete for incremental backup table set
   * @param backupRoot backup root
   * @return delete operation
   */
  private Delete createDeleteForIncrBackupTableSet(String backupRoot) {
    Delete delete = new Delete(rowkey(INCR_BACKUP_SET, backupRoot));
    delete.addFamily(BackupSystemTable.META_FAMILY);
    return delete;
  }

  /**
   * Creates Scan operation to load backup history
   * @return scan operation
   */
  private Scan createScanForBackupHistory() {
    Scan scan = new Scan();
    byte[] startRow = Bytes.toBytes(BACKUP_INFO_PREFIX);
    byte[] stopRow = Arrays.copyOf(startRow, startRow.length);
    stopRow[stopRow.length - 1] = (byte) (stopRow[stopRow.length - 1] + 1);
    scan.setStartRow(startRow);
    scan.setStopRow(stopRow);
    scan.addFamily(BackupSystemTable.SESSIONS_FAMILY);
    scan.setMaxVersions(1);
    return scan;
  }

  /**
   * Converts cell to backup info instance.
   * @param current current cell
   * @return backup backup info instance
   * @throws IOException exception
   */
  private BackupInfo cellToBackupInfo(Cell current) throws IOException {
    byte[] data = CellUtil.cloneValue(current);
    return BackupInfo.fromByteArray(data);
  }

  /**
   * Creates Put to write RS last roll log timestamp map
   * @param table table
   * @param smap map, containing RS:ts
   * @return put operation
   */
  private Put createPutForWriteRegionServerLogTimestamp(TableName table, byte[] smap,
      String backupRoot) {
    Put put = new Put(rowkey(TABLE_RS_LOG_MAP_PREFIX, backupRoot, NULL, table.getNameAsString()));
    put.addColumn(BackupSystemTable.META_FAMILY, Bytes.toBytes("log-roll-map"), smap);
    return put;
  }

  /**
   * Creates Scan to load table-> { RS -> ts} map of maps
   * @return scan operation
   */
  private Scan createScanForReadLogTimestampMap(String backupRoot) {
    Scan scan = new Scan();
    byte[] startRow = rowkey(TABLE_RS_LOG_MAP_PREFIX, backupRoot);
    byte[] stopRow = Arrays.copyOf(startRow, startRow.length);
    stopRow[stopRow.length - 1] = (byte) (stopRow[stopRow.length - 1] + 1);
    scan.setStartRow(startRow);
    scan.setStopRow(stopRow);
    scan.addFamily(BackupSystemTable.META_FAMILY);

    return scan;
  }

  /**
   * Get table name from rowkey
   * @param cloneRow rowkey
   * @return table name
   */
  private String getTableNameForReadLogTimestampMap(byte[] cloneRow) {
    String s = Bytes.toString(cloneRow);
    int index = s.lastIndexOf(NULL);
    return s.substring(index + 1);
  }

  /**
   * Creates Put to store RS last log result
   * @param server server name
   * @param timestamp log roll result (timestamp)
   * @return put operation
   */
  private Put createPutForRegionServerLastLogRollResult(String server, Long timestamp,
      String backupRoot) {
    Put put = new Put(rowkey(RS_LOG_TS_PREFIX, backupRoot, NULL, server));
    put.addColumn(BackupSystemTable.META_FAMILY, Bytes.toBytes("rs-log-ts"),
      Bytes.toBytes(timestamp));
    return put;
  }

  /**
   * Creates Scan operation to load last RS log roll results
   * @return scan operation
   */
  private Scan createScanForReadRegionServerLastLogRollResult(String backupRoot) {
    Scan scan = new Scan();
    byte[] startRow = rowkey(RS_LOG_TS_PREFIX, backupRoot);
    byte[] stopRow = Arrays.copyOf(startRow, startRow.length);
    stopRow[stopRow.length - 1] = (byte) (stopRow[stopRow.length - 1] + 1);
    scan.setStartRow(startRow);
    scan.setStopRow(stopRow);
    scan.addFamily(BackupSystemTable.META_FAMILY);
    scan.setMaxVersions(1);

    return scan;
  }

  /**
   * Get server's name from rowkey
   * @param row rowkey
   * @return server's name
   */
  private String getServerNameForReadRegionServerLastLogRollResult(byte[] row) {
    String s = Bytes.toString(row);
    int index = s.lastIndexOf(NULL);
    return s.substring(index + 1);
  }

  /**
   * Creates put list for list of WAL files
   * @param files list of WAL file paths
   * @param backupId backup id
   * @return put list
   * @throws IOException exception
   */
  private List<Put> createPutsForAddWALFiles(List<String> files, String backupId,
      String backupRoot) throws IOException {

    List<Put> puts = new ArrayList<Put>();
    for (String file : files) {
      Put put = new Put(rowkey(WALS_PREFIX, BackupUtils.getUniqueWALFileNamePart(file)));
      put.addColumn(BackupSystemTable.META_FAMILY, Bytes.toBytes("backupId"),
        Bytes.toBytes(backupId));
      put.addColumn(BackupSystemTable.META_FAMILY, Bytes.toBytes("file"), Bytes.toBytes(file));
      put.addColumn(BackupSystemTable.META_FAMILY, Bytes.toBytes("root"), Bytes.toBytes(backupRoot));
      puts.add(put);
    }
    return puts;
  }

  /**
   * Creates Scan operation to load WALs
   * @param backupRoot path to backup destination
   * @return scan operation
   */
  private Scan createScanForGetWALs(String backupRoot) {
    // TODO: support for backupRoot
    Scan scan = new Scan();
    byte[] startRow = Bytes.toBytes(WALS_PREFIX);
    byte[] stopRow = Arrays.copyOf(startRow, startRow.length);
    stopRow[stopRow.length - 1] = (byte) (stopRow[stopRow.length - 1] + 1);
    scan.setStartRow(startRow);
    scan.setStopRow(stopRow);
    scan.addFamily(BackupSystemTable.META_FAMILY);
    return scan;
  }

  /**
   * Creates Get operation for a given wal file name TODO: support for backup destination
   * @param file file
   * @return get operation
   * @throws IOException exception
   */
  private Get createGetForCheckWALFile(String file) throws IOException {
    Get get = new Get(rowkey(WALS_PREFIX, BackupUtils.getUniqueWALFileNamePart(file)));
    // add backup root column
    get.addFamily(BackupSystemTable.META_FAMILY);
    return get;
  }

  /**
   * Creates Scan operation to load backup set list
   * @return scan operation
   */
  private Scan createScanForBackupSetList() {
    Scan scan = new Scan();
    byte[] startRow = Bytes.toBytes(SET_KEY_PREFIX);
    byte[] stopRow = Arrays.copyOf(startRow, startRow.length);
    stopRow[stopRow.length - 1] = (byte) (stopRow[stopRow.length - 1] + 1);
    scan.setStartRow(startRow);
    scan.setStopRow(stopRow);
    scan.addFamily(BackupSystemTable.META_FAMILY);
    return scan;
  }

  /**
   * Creates Get operation to load backup set content
   * @return get operation
   */
  private Get createGetForBackupSet(String name) {
    Get get = new Get(rowkey(SET_KEY_PREFIX, name));
    get.addFamily(BackupSystemTable.META_FAMILY);
    return get;
  }

  /**
   * Creates Delete operation to delete backup set content
   * @param name backup set's name
   * @return delete operation
   */
  private Delete createDeleteForBackupSet(String name) {
    Delete del = new Delete(rowkey(SET_KEY_PREFIX, name));
    del.addFamily(BackupSystemTable.META_FAMILY);
    return del;
  }

  /**
   * Creates Put operation to update backup set content
   * @param name backup set's name
   * @param tables list of tables
   * @return put operation
   */
  private Put createPutForBackupSet(String name, String[] tables) {
    Put put = new Put(rowkey(SET_KEY_PREFIX, name));
    byte[] value = convertToByteArray(tables);
    put.addColumn(BackupSystemTable.META_FAMILY, Bytes.toBytes("tables"), value);
    return put;
  }

  private byte[] convertToByteArray(String[] tables) {
    return StringUtils.join(tables, ",").getBytes();
  }

  /**
   * Converts cell to backup set list.
   * @param current current cell
   * @return backup set as array of table names
   * @throws IOException
   */
  private String[] cellValueToBackupSet(Cell current) throws IOException {
    byte[] data = CellUtil.cloneValue(current);
    if (data != null && data.length > 0) {
      return Bytes.toString(data).split(",");
    } else {
      return new String[0];
    }
  }

  /**
   * Converts cell key to backup set name.
   * @param current current cell
   * @return backup set name
   * @throws IOException
   */
  private String cellKeyToBackupSetName(Cell current) throws IOException {
    byte[] data = CellUtil.cloneRow(current);
    return Bytes.toString(data).substring(SET_KEY_PREFIX.length());
  }

  private byte[] rowkey(String s, String... other) {
    StringBuilder sb = new StringBuilder(s);
    for (String ss : other) {
      sb.append(ss);
    }
    return sb.toString().getBytes();
  }


}