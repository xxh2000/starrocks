// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.sql.analyzer;

import com.starrocks.common.Config;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.InsertStmt;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * INSERT statement interceptor.
 * <p>
 * This interceptor can block user INSERT operations based on configuration,
 * while allowing system operations (e.g., materialized view refresh) to proceed.
 * <p>
 * Intercept check priority:
 * 1. System operation (isSystem=true) → Allow
 * 2. Feature disabled (enable_insert_intercept=false) → Allow
 * 3. Root user → Allow
 * 4. Session bypass (allow_insert_bypass=true) → Allow
 * 5. Database in whitelist → Allow
 * 6. Otherwise → Block
 */
public class InsertInterceptor {
    private static final Logger LOG = LogManager.getLogger(InsertInterceptor.class);

    /**
     * Check if the INSERT statement should be intercepted.
     *
     * @param insertStmt the INSERT statement to check
     * @param context    the connection context
     * @throws SemanticException if the INSERT is blocked
     */
    public static void check(InsertStmt insertStmt, ConnectContext context) {
        // 1. System operation bypass (e.g., materialized view refresh)
        if (insertStmt.isSystem()) {
            LOG.info("INSERT interceptor bypassed: system operation");
            return;
        }

        // 2. Feature not enabled
        if (!Config.enable_insert_intercept) {
            return;
        }

        // 3. Root user bypass
        if (isRootUser(context)) {
            LOG.debug("INSERT interceptor bypassed: root user");
            return;
        }

        // 4. Session variable bypass
        if (context != null && context.getSessionVariable().isAllowInsertBypass()) {
            LOG.info("INSERT interceptor bypassed: session variable allow_insert_bypass=true");
            return;
        }

        // 5. Get target database name
        String dbName = getTargetDatabase(insertStmt, context);

        // 6. Check whitelist
        if (isInWhitelist(dbName)) {
            LOG.debug("INSERT interceptor bypassed: database '{}' in whitelist", dbName);
            return;
        }

        // 7. Block and report error
        String tableName = insertStmt.getTableName() != null ? insertStmt.getTableName().getTbl() : "unknown";
        LOG.warn("INSERT intercepted: database='{}', table='{}'", dbName, tableName);

        ErrorReport.reportSemanticException(
                ErrorCode.ERR_INSERT_INTERCEPTED,
                dbName != null ? dbName : "unknown",
                tableName
        );
    }

    /**
     * Get the target database name from the INSERT statement.
     */
    private static String getTargetDatabase(InsertStmt insertStmt, ConnectContext context) {
        String dbName = null;

        if (insertStmt.getTableName() != null) {
            dbName = insertStmt.getTableName().getDb();
        }

        // If database name is not specified, use the current session's default database
        if (StringUtils.isEmpty(dbName) && context != null) {
            dbName = context.getDatabase();
        }

        return dbName;
    }

    /**
     * Check if the current user is root.
     */
    private static boolean isRootUser(ConnectContext context) {
        if (context == null) {
            return false;
        }
        String user = context.getQualifiedUser();
        return "root".equalsIgnoreCase(user);
    }

    /**
     * Check if the database is in the whitelist.
     */
    private static boolean isInWhitelist(String dbName) {
        if (StringUtils.isEmpty(dbName)) {
            return false;
        }

        String whitelistConfig = Config.insert_intercept_whitelist_dbs;
        if (StringUtils.isEmpty(whitelistConfig)) {
            return false;
        }

        // Parse whitelist configuration
        Set<String> whitelist = parseWhitelist(whitelistConfig);

        // Case-insensitive match
        return whitelist.stream()
                .anyMatch(db -> db.equalsIgnoreCase(dbName));
    }

    /**
     * Parse the whitelist configuration string.
     */
    private static Set<String> parseWhitelist(String config) {
        Set<String> result = new HashSet<>();
        for (String db : config.split(",")) {
            String trimmed = db.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Get the current whitelist databases (for SHOW commands, etc.)
     */
    public static Set<String> getWhitelistDatabases() {
        return parseWhitelist(Config.insert_intercept_whitelist_dbs);
    }
}
