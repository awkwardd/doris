// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.analysis;

import org.apache.doris.analysis.CompoundPredicate.Operator;
import org.apache.doris.catalog.Env;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.ErrorCode;
import org.apache.doris.common.ErrorReport;
import org.apache.doris.common.FeNameFormat;
import org.apache.doris.common.UserException;
import org.apache.doris.common.util.InternalDatabaseUtil;
import org.apache.doris.mysql.privilege.PrivBitSet;
import org.apache.doris.mysql.privilege.PrivPredicate;
import org.apache.doris.mysql.privilege.Privilege;
import org.apache.doris.qe.ConnectContext;

import com.google.common.base.Strings;

public class AlterDatabaseRename extends DdlStmt {
    private String dbName;
    private String newDbName;

    public AlterDatabaseRename(String dbName, String newDbName) {
        this.dbName = dbName;
        this.newDbName = newDbName;
    }

    public String getDbName() {
        return dbName;
    }

    public String getNewDbName() {
        return newDbName;
    }

    @Override
    public void analyze(Analyzer analyzer) throws AnalysisException, UserException {
        super.analyze(analyzer);
        if (Strings.isNullOrEmpty(dbName)) {
            throw new AnalysisException("Database name is not set");
        }
        InternalDatabaseUtil.checkDatabase(dbName, ConnectContext.get());
        if (!Env.getCurrentEnv().getAccessManager().checkDbPriv(ConnectContext.get(), dbName,
                PrivPredicate.of(PrivBitSet.of(Privilege.ADMIN_PRIV, Privilege.ALTER_PRIV), Operator.OR))) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_DBACCESS_DENIED_ERROR,
                    analyzer.getQualifiedUser(), dbName);
        }

        if (Strings.isNullOrEmpty(newDbName)) {
            throw new AnalysisException("New database name is not set");
        }

        FeNameFormat.checkDbName(newDbName);
    }

    @Override
    public String toSql() {
        return "ALTER DATABASE " + dbName + " RENAME " + newDbName;
    }

}
