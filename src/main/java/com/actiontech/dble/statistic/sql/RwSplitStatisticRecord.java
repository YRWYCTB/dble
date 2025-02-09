/*
 * Copyright (C) 2016-2022 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.statistic.sql;

import com.actiontech.dble.backend.mysql.nio.MySQLInstance;
import com.actiontech.dble.config.ErrorCode;
import com.actiontech.dble.net.connection.BackendConnection;
import com.actiontech.dble.net.mysql.ErrorPacket;
import com.actiontech.dble.server.parser.ServerParseFactory;
import com.actiontech.dble.services.BusinessService;
import com.actiontech.dble.statistic.sql.entry.StatisticBackendSqlEntry;
import com.actiontech.dble.statistic.sql.entry.StatisticFrontendSqlEntry;
import com.actiontech.dble.statistic.sql.entry.StatisticTxEntry;

import java.util.LinkedList;

public class RwSplitStatisticRecord extends StatisticRecord {

    protected volatile LinkedList<StatisticFrontendSqlEntry> multiFrontendSqlEntry = null;
    protected volatile int multiIndex = -1;

    public void onTxPreStart() {
        /*if (frontendSqlEntry != null) {
            frontendSqlEntry.setNeedToTx(false);
        }*/
    }

    public void onTxStartBySet(BusinessService businessService) {
        onTxStart(businessService, false, true);
    }

    public void onTxStart(BusinessService businessService, boolean isImplicitly, boolean isSet) {
        if (isImplicitly) {
            txid = STATISTIC.getIncrementVirtualTxID();
        }
        txEntry = new StatisticTxEntry(frontendInfo, xaId, txid, System.nanoTime(), isImplicitly);
        if (!isImplicitly && !isSet) {
            if (frontendSqlEntry != null)
                txEntry.add(frontendSqlEntry);
        }
    }

    public void onTxEnd() {
        if (txEntry != null) {
            long txEndTime = System.nanoTime();
            if (frontendSqlEntry != null && frontendSqlEntry.getAllEndTime() == 0L) {
                frontendSqlEntry.setAllEndTime(txEndTime);
                txEntry.add(frontendSqlEntry);
            }
            frontendSqlEntry = null;
            txEntry.setAllEndTime(txEndTime);
            pushTx();
        }
    }

    public void onFrontendSqlEnd() {
        if (frontendSqlEntry != null) {
            if (frontendSqlEntry.getSql() == null) {
                onFrontendSqlClose();
            } else {
                frontendSqlEntry.setAllEndTime(System.nanoTime());
                onTxData(frontendSqlEntry);
                pushFrontendSql();
            }
        }
    }

    public void onFrontendMultiSqlStart() {
        multiFrontendSqlEntry = new LinkedList<>();
    }

    public void onBackendSqlStart(BackendConnection connection) {
        if (isMultiFlag()) {
            onMultiSqlStart(connection);
            return;
        }
        if (!isMultiFlag() && frontendSqlEntry != null) {
            StatisticBackendSqlEntry entry = new StatisticBackendSqlEntry(
                    frontendInfo,
                    ((MySQLInstance) connection.getInstance()).getName(), connection.getHost(), connection.getPort(), "-",
                    frontendSqlEntry.getSql(), System.nanoTime());
            frontendSqlEntry.put("&statistic_rw_key", entry);
        }
    }

    public void onBackendSqlSetRowsAndEnd(long rows) {
        if (isMultiFlag()) {
            onBackendMultiSqlSetRowsAndEnd(rows);
            return;
        }
        if (frontendSqlEntry != null) {
            if (frontendSqlEntry.getBackendSqlEntry("&statistic_rw_key") == null)
                return;
            frontendSqlEntry.getBackendSqlEntry("&statistic_rw_key").setRows(rows);
            frontendSqlEntry.getBackendSqlEntry("&statistic_rw_key").setAllEndTime(System.nanoTime());
            frontendSqlEntry.getBackendSqlEntry("&statistic_rw_key").setNeedToTx(frontendSqlEntry.isNeedToTx());
            frontendSqlEntry.setRowsAndExaminedRows(rows);
            pushBackendSql(frontendSqlEntry.getBackendSqlEntry("&statistic_rw_key"));
            onFrontendSqlEnd();
        }
    }

    public void onBackendSqlError(byte[] data) {
        if (isMultiFlag()) {
            onBackendMultiSqlError();
            return;
        }
        ErrorPacket errPg = new ErrorPacket();
        errPg.read(data);
        if (errPg.getErrNo() == ErrorCode.ER_PARSE_ERROR) {
            onFrontendSqlClose();
            return;
        }
        onBackendSqlSetRowsAndEnd(0);
    }

    void onMultiSqlStart(BackendConnection connection) {
        LinkedList<String> splitSql = new LinkedList<>();
        ServerParseFactory.getRwSplitParser().getMultiStatement(frontendSqlEntry.getSql(), splitSql);
        if (splitSql.isEmpty()) {
            multiFrontendSqlEntry = null;
            return;
        }
        long time = System.nanoTime();
        if (txEntry == null) {
            txEntry = new StatisticTxEntry(frontendInfo, xaId, txid, time, true);
        }
        for (String sql : splitSql) {
            StatisticFrontendSqlEntry frontendSqlEntry = new StatisticFrontendSqlEntry(frontendInfo, time);
            frontendSqlEntry.put("&statistic_rw_key", new StatisticBackendSqlEntry(
                    frontendInfo,
                    ((MySQLInstance) connection.getInstance()).getName(), connection.getHost(), connection.getPort(), "-",
                    sql, time));
            frontendSqlEntry.setSql(sql);
            frontendSqlEntry.setSchema(frontendSqlEntry.getSchema());
            frontendSqlEntry.setTxId(txid);
            frontendSqlEntry.setNeedToTx(false);
            multiFrontendSqlEntry.add(frontendSqlEntry);
        }
        frontendSqlEntry = null;
    }

    void onBackendMultiSqlSetRowsAndEnd(long rows) {
        long time = System.nanoTime();
        if (++multiIndex + 1 > multiFrontendSqlEntry.size()) return;
        StatisticFrontendSqlEntry f = multiFrontendSqlEntry.get(multiIndex);
        StatisticBackendSqlEntry b = f.getBackendSqlEntry("&statistic_rw_key");
        b.setRows(rows);
        b.setAllEndTime(time);
        f.setRowsAndExaminedRows(rows);
        f.setAllEndTime(time);
        pushBackendSql(b);
        pushFrontendSql(f);
        txEntry.add(f);
        if (multiIndex == multiFrontendSqlEntry.size() - 1) {
            txEntry.setAllEndTime(time);
            pushTx();
            restMultiFlag();
        }
    }

    void onBackendMultiSqlError() {
        long time = System.nanoTime();
        if (++multiIndex + 1 > multiFrontendSqlEntry.size()) return;
        StatisticFrontendSqlEntry f = multiFrontendSqlEntry.get(multiIndex);
        StatisticBackendSqlEntry b = f.getBackendSqlEntry("&statistic_rw_key");
        b.setRows(0);
        b.setAllEndTime(time);
        f.setRowsAndExaminedRows(0);
        f.setAllEndTime(time);
        pushBackendSql(b);
        pushFrontendSql(f);
        txEntry.add(f);
        txEntry.setAllEndTime(time);
        pushTx();
        restMultiFlag();
    }

    boolean isMultiFlag() {
        return multiFrontendSqlEntry != null;
    }

    void restMultiFlag() {
        multiFrontendSqlEntry.clear();
        multiFrontendSqlEntry = null;
        multiIndex = -1;
    }

    public void pushFrontendSql(StatisticFrontendSqlEntry f) {
        if (f != null) {
            StatisticManager.getInstance().push(f);
        }
    }

    public RwSplitStatisticRecord(BusinessService service) {
        super(service);
    }
}
