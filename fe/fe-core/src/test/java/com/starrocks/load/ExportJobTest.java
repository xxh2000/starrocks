// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.
package com.starrocks.load;

import com.starrocks.common.UserException;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.system.SystemInfoService;
import com.starrocks.thrift.TInternalScanRange;
import com.starrocks.thrift.TNetworkAddress;
import com.starrocks.thrift.TScanRange;
import com.starrocks.thrift.TScanRangeLocation;
import com.starrocks.thrift.TScanRangeLocations;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

public class ExportJobTest {
    @Mocked
    private SystemInfoService systemInfoService;

    @Before
    public void setUp() {
        new MockUp<GlobalStateMgr>() {
            @Mock
            public SystemInfoService getCurrentSystemInfo() {
                return systemInfoService;
            }
        };
    }

    @Test
    public void testSelectOneAvailableLocationSkipsUnavailableBackend() throws Exception {
        new Expectations(systemInfoService) {
            {
                systemInfoService.checkBackendAvailable(1L);
                result = false;
                systemInfoService.checkBackendAvailable(2L);
                result = true;
            }
        };

        List<TScanRangeLocation> selectedLocations = selectOneAvailableLocation(
                new ExportJob(), createTabletLocations(10086L, 1L, 2L));

        Assert.assertEquals(1, selectedLocations.size());
        Assert.assertEquals(2L, selectedLocations.get(0).getBackend_id());
    }

    @Test
    public void testSelectOneAvailableLocationThrowsWhenAllBackendsUnavailable() throws Exception {
        new Expectations(systemInfoService) {
            {
                systemInfoService.checkBackendAvailable(anyLong);
                result = false;
                minTimes = 0;
            }
        };

        try {
            selectOneAvailableLocation(new ExportJob(), createTabletLocations(10087L, 1L, 2L));
            Assert.fail("Expected UserException");
        } catch (InvocationTargetException e) {
            Assert.assertTrue(e.getCause() instanceof UserException);
            Assert.assertTrue(e.getCause().getMessage().contains("10087"));
        }
    }

    @SuppressWarnings("unchecked")
    private List<TScanRangeLocation> selectOneAvailableLocation(ExportJob job, TScanRangeLocations tablet)
            throws Exception {
        Method method = ExportJob.class.getDeclaredMethod("selectOneAvailableLocation", TScanRangeLocations.class);
        method.setAccessible(true);
        return (List<TScanRangeLocation>) method.invoke(job, tablet);
    }

    private TScanRangeLocations createTabletLocations(long tabletId, long... backendIds) {
        TInternalScanRange internalScanRange = new TInternalScanRange();
        internalScanRange.setTablet_id(tabletId);
        TScanRange scanRange = new TScanRange();
        scanRange.setInternal_scan_range(internalScanRange);

        TScanRangeLocations scanRangeLocations = new TScanRangeLocations();
        scanRangeLocations.setScan_range(scanRange);
        for (long backendId : backendIds) {
            TScanRangeLocation location = new TScanRangeLocation(new TNetworkAddress("host" + backendId, 9050));
            location.setBackend_id(backendId);
            scanRangeLocations.addToLocations(location);
        }
        return scanRangeLocations;
    }
}
