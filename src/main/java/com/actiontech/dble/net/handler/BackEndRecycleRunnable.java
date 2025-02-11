/*
 * Copyright (C) 2016-2022 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.net.handler;

import com.actiontech.dble.net.connection.BackendConnection;
import com.actiontech.dble.services.mysqlsharding.MySQLResponseService;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by szf on 2018/7/12.
 */
public class BackEndRecycleRunnable implements Runnable, BackEndCleaner {

    private final MySQLResponseService service;
    private ReentrantLock lock = new ReentrantLock();
    private Condition condRelease = this.lock.newCondition();

    public BackEndRecycleRunnable(MySQLResponseService service) {
        this.service = service;
        service.setRecycler(this);
    }


    @Override
    public void run() {
        BackendConnection conn = service.getConnection();
        if (conn.isClosed()) {
            return;
        }

        try {
            lock.lock();
            try {
                if (service.isRowDataFlowing()) {
                    if (!condRelease.await(10, TimeUnit.MILLISECONDS)) {
                        if (!conn.isClosed()) {
                            conn.businessClose("recycle time out");
                        }
                    } else {
                        service.release();
                    }
                } else {
                    service.release();
                }
            } catch (Exception e) {
                service.getConnection().businessClose("recycle exception");
            } finally {
                lock.unlock();
            }
        } catch (Throwable e) {
            service.getConnection().businessClose("recycle exception");
        }
    }


    public void signal() {
        if (lock.tryLock()) {
            try {
                condRelease.signal();
            } finally {
                lock.unlock();
            }
        }
    }

}
