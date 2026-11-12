package io.github.abdurazaaqmohammed.features.files.network;

import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteSpaceTest {
    @Test public void usedSpaceIsDerivedFromTheReportedTotalAndFree() {
        RemoteSpace space = RemoteSpace.fromTotalFree(100, 25);
        assertEquals(75, space.usedBytes);
        assertEquals(25, space.freeBytes);
    }

    @Test public void quotaTotalsAreUsedPlusAvailable() {
        assertEquals(100, RemoteSpace.fromQuota(75, 25).totalBytes);
        assertEquals(0, RemoteSpace.fromQuota(0, 0).totalBytes);
    }

    @Test public void freeOnlyDoesNotInventUsedOrTotalSpace() {
        RemoteSpace space = RemoteSpace.available(25);
        assertEquals(-1, space.totalBytes);
        assertEquals(-1, space.usedBytes);
        assertEquals(25, space.freeBytes);
    }

    @Test public void invalidOrOverflowedSpaceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RemoteSpace.fromTotalFree(10, 11));
        assertThrows(IllegalArgumentException.class, () -> RemoteSpace.fromQuota(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> RemoteSpace.available(-1));
        assertThrows(ArithmeticException.class, () -> RemoteSpace.fromQuota(Long.MAX_VALUE, 1));
    }

}
