// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.
package com.starrocks.sql.common;

import com.starrocks.qe.SessionVariable;
import org.junit.Assert;
import org.junit.Test;

public class SqlDigestUtilsTest {
    @Test
    public void testBuildSqlDigest() {
        SessionVariable sessionVariable = new SessionVariable();
        SqlDigestUtils.DigestResult first = SqlDigestUtils.build(
                "select * from tbl where id = 1 limit 10", sessionVariable);
        SqlDigestUtils.DigestResult second = SqlDigestUtils.build(
                "select * from tbl where id = 2 limit 20", sessionVariable);

        Assert.assertEquals(first.getNormalizedSql(), second.getNormalizedSql());
        Assert.assertEquals(first.getDigest(), second.getDigest());
        Assert.assertTrue(first.getNormalizedSql().contains("?"));
        Assert.assertEquals(32, first.getDigest().length());
    }

    @Test
    public void testRejectMultipleStatements() {
        try {
            SqlDigestUtils.build("select 1; select 2", new SessionVariable());
            Assert.fail("multiple statements should be rejected");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("Only one SQL statement is supported", e.getMessage());
        }
    }
}
