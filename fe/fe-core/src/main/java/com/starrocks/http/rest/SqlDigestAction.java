// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.
package com.starrocks.http.rest;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.IllegalArgException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.common.SqlDigestUtils;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

public class SqlDigestAction extends RestBaseAction {
    private static final Gson GSON = new Gson();
    private static final String SQL_KEY = "sql";

    public SqlDigestAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.POST, "/api/sql_digest", new SqlDigestAction(controller));
    }

    @Override
    public void execute(BaseRequest request, BaseResponse response) {
        response.setContentType("application/json");

        ConnectContext context = new ConnectContext(null);
        context.setGlobalStateMgr(GlobalStateMgr.getCurrentState());
        context.setQueryId(UUIDUtil.genUUID());
        context.setThreadLocalInfo();
        try {
            String sql = parseSql(request.getContent());
            if (Strings.isNullOrEmpty(sql)) {
                sendError(request, response, "SQL is required");
                return;
            }

            SqlDigestUtils.DigestResult digestResult = SqlDigestUtils.build(sql, context.getSessionVariable());
            response.getContent().append(GSON.toJson(new SqlDigestResult(
                    digestResult.getNormalizedSql(), digestResult.getDigest())));
            sendResult(request, response);
        } catch (Exception e) {
            sendError(request, response, e.getMessage());
        } finally {
            ConnectContext.remove();
        }
    }

    private String parseSql(String content) {
        if (Strings.isNullOrEmpty(content)) {
            return "";
        }

        String trimmedContent = content.trim();
        if (!trimmedContent.startsWith("{")) {
            return content;
        }

        JsonObject jsonObject = JsonParser.parseString(trimmedContent).getAsJsonObject();
        if (!jsonObject.has(SQL_KEY) || jsonObject.get(SQL_KEY).isJsonNull()) {
            return "";
        }
        return jsonObject.get(SQL_KEY).getAsString();
    }

    private void sendError(BaseRequest request, BaseResponse response, String message) {
        response.getContent().append(new RestBaseResult(message).toJson());
        sendResult(request, response, HttpResponseStatus.BAD_REQUEST);
    }

    private static class SqlDigestResult extends RestBaseResult {
        @SerializedName("normalized_sql")
        private final String normalizedSql;
        private final String digest;

        private SqlDigestResult(String normalizedSql, String digest) {
            this.normalizedSql = normalizedSql;
            this.digest = digest;
        }

        public String getNormalizedSql() {
            return normalizedSql;
        }

        public String getDigest() {
            return digest;
        }
    }
}
