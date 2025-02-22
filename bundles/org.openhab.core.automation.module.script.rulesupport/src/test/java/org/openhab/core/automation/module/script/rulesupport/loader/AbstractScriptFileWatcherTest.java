/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.automation.module.script.rulesupport.loader;

import static java.nio.file.StandardWatchEventKinds.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.openhab.core.OpenHAB.CONFIG_DIR_PROG_ARGUMENT;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import javax.script.ScriptEngine;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.core.automation.module.script.ScriptDependencyTracker;
import org.openhab.core.automation.module.script.ScriptEngineContainer;
import org.openhab.core.automation.module.script.ScriptEngineFactory;
import org.openhab.core.automation.module.script.ScriptEngineManager;
import org.openhab.core.automation.module.script.rulesupport.internal.loader.ScriptFileReference;
import org.openhab.core.service.ReadyMarker;
import org.openhab.core.service.ReadyService;
import org.openhab.core.service.StartLevelService;
import org.openhab.core.test.java.JavaTest;
import org.opentest4j.AssertionFailedError;

/**
 * Test for {@link AbstractScriptFileWatcher}, covering differing start levels and dependency tracking
 *
 * @author Jonathan Gilbert - Initial contribution
 * @author Jan N. Klug - Refactoring and improvements
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbstractScriptFileWatcherTest extends JavaTest {

    private boolean watchSubDirectories = true;
    private @NonNullByDefault({}) AbstractScriptFileWatcher scriptFileWatcher;

    private @Mock @NonNullByDefault({}) ScriptEngineManager scriptEngineManagerMock;
    private @Mock @NonNullByDefault({}) ScriptDependencyTracker scriptDependencyTrackerMock;
    private @Mock @NonNullByDefault({}) StartLevelService startLevelServiceMock;
    private @Mock @NonNullByDefault({}) ReadyService readyServiceMock;

    protected @NonNullByDefault({}) @TempDir Path tempScriptDir;

    private final AtomicInteger atomicInteger = new AtomicInteger();

    private int currentStartLevel = 0;

    @BeforeEach
    public void setUp() {
        System.setProperty(CONFIG_DIR_PROG_ARGUMENT, tempScriptDir.toString());

        atomicInteger.set(0);
        currentStartLevel = 0;

        // ensure initialize is not called on initialization
        when(startLevelServiceMock.getStartLevel()).thenAnswer(invocation -> currentStartLevel);

        scriptFileWatcher = createScriptFileWatcher();
    }

    @AfterEach
    public void tearDown() {
        scriptFileWatcher.deactivate();
    }

    @Test
    public void testLoadOneDefaultFileAlreadyStarted() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);
        updateStartLevel(100);

        Path p = getFile("script.js");

        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p);

        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p.toString());
    }

    @Test
    public void testSubDirectoryIncludedInInitialImport() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);
        Path p0 = getFile("script.js");
        Path p1 = getFile("dir/script.js");

        updateStartLevel(100);

        awaitEmptyQueue();

        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js",
                ScriptFileReference.getScriptIdentifier(p0));
        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js",
                ScriptFileReference.getScriptIdentifier(p1));
    }

    @Test
    public void testSubDirectoryIgnoredInInitialImport() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);
        watchSubDirectories = false;
        Path p0 = getFile("script.js");
        Path p1 = getFile("dir/script.js");

        updateStartLevel(100);

        awaitEmptyQueue();

        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js",
                ScriptFileReference.getScriptIdentifier(p0));
        verify(scriptEngineManagerMock, never()).createScriptEngine("js", ScriptFileReference.getScriptIdentifier(p1));
    }

    @Test
    public void testLoadOneDefaultFileWaitUntilStarted() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);
        updateStartLevel(20);

        Path p = getFile("script.js");
        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p);

        awaitEmptyQueue();

        // verify not yet called
        verify(scriptEngineManagerMock, never()).createScriptEngine(anyString(), anyString());

        // verify is called when the start level increases
        updateStartLevel(100);

        awaitEmptyQueue();

        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p.toString());
    }

    @Test
    public void testLoadOneCustomFileWaitUntilStarted() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);

        updateStartLevel(50);

        Path p = getFile("script.sl60.js");
        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p);

        awaitEmptyQueue();

        // verify not yet called
        verify(scriptEngineManagerMock, never()).createScriptEngine(anyString(), anyString());

        // verify is called when the start level increases
        updateStartLevel(100);

        awaitEmptyQueue();

        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p.toString());
    }

    @Test
    public void testLoadTwoCustomFilesDifferentStartLevels() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);
        updateStartLevel(20);

        Path p1 = getFile("script.sl70.js");
        Path p2 = getFile("script.sl50.js");
        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p1);
        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p2);

        awaitEmptyQueue();

        // verify not yet called
        verify(scriptEngineManagerMock, never()).createScriptEngine(anyString(), anyString());

        updateStartLevel(40);

        awaitEmptyQueue();

        // verify not yet called
        verify(scriptEngineManagerMock, never()).createScriptEngine(anyString(), anyString());

        updateStartLevel(60);

        awaitEmptyQueue();

        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p2.toString());
        verify(scriptEngineManagerMock, never()).createScriptEngine(anyString(), eq(p1.toString()));

        updateStartLevel(80);

        awaitEmptyQueue();

        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p1.toString());
    }

    @Test
    public void testLoadTwoCustomFilesAlternativePatternDifferentStartLevels() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);

        Path p1 = getFile("sl70/script.js");
        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p1);
        Path p2 = getFile("sl50/script.js");
        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p2);

        // verify not yet called
        verify(scriptEngineManagerMock, never()).createScriptEngine(anyString(), anyString());

        updateStartLevel(40);

        // verify not yet called
        verify(scriptEngineManagerMock, never()).createScriptEngine(anyString(), anyString());

        updateStartLevel(60);

        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p2.toString());
        verify(scriptEngineManagerMock, never()).createScriptEngine(anyString(), eq(p1.toString()));

        updateStartLevel(80);

        awaitEmptyQueue();

        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p1.toString());
    }

    @Test
    public void testLoadOneDefaultFileDelayedSupport() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(false);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);
        updateStartLevel(100);

        Path p = getFile("script.js");
        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p);

        // verify not yet called but checked
        waitForAssert(() -> verify(scriptEngineManagerMock).isSupported(anyString()));
        verify(scriptEngineManagerMock, never()).createScriptEngine(anyString(), anyString());

        // add support is added for .js files
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        scriptFileWatcher.factoryAdded("js");

        awaitEmptyQueue();

        // verify script has now been processed
        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p.toString());
    }

    @Test
    public void testOrderingWithinSingleStartLevel() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);
        updateStartLevel(50);

        Path p64 = getFile("script.sl64.js");
        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p64);
        Path p66 = getFile("script.sl66.js");
        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p66);
        Path p65 = getFile("script.sl65.js");
        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p65);

        updateStartLevel(70);

        awaitEmptyQueue();

        InOrder inOrder = inOrder(scriptEngineManagerMock);

        inOrder.verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p64.toString());
        inOrder.verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p65.toString());
        inOrder.verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p66.toString());
    }

    @Test
    public void testOrderingStartlevelFolders() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);

        Path p50 = getFile("a_script.js");
        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p50);
        Path p40 = getFile("sl40/b_script.js");
        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p40);
        Path p30 = getFile("sl30/script.js");
        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p30);

        awaitEmptyQueue();

        updateStartLevel(70);

        awaitEmptyQueue();

        InOrder inOrder = inOrder(scriptEngineManagerMock);
        inOrder.verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p30.toString());
        inOrder.verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p40.toString());
        inOrder.verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p50.toString());
    }

    @Test
    public void testReloadActiveWhenDependencyChanged() {
        ScriptEngineFactory scriptEngineFactoryMock = mock(ScriptEngineFactory.class);
        when(scriptEngineFactoryMock.getDependencyTracker()).thenReturn(scriptDependencyTrackerMock);
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineContainer.getFactory()).thenReturn(scriptEngineFactoryMock);
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);

        updateStartLevel(100);

        Path p = getFile("script.js");

        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p);

        awaitEmptyQueue();

        verify(scriptEngineManagerMock, timeout(10000).times(1)).createScriptEngine("js", p.toString());

        scriptFileWatcher.onDependencyChange(p.toString());

        awaitEmptyQueue();

        verify(scriptEngineManagerMock, timeout(10000).times(2)).createScriptEngine("js", p.toString());
    }

    @Test
    public void testNotReloadInactiveWhenDependencyChanged() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);
        updateStartLevel(100);

        Path p = getFile("script.js");

        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p);

        awaitEmptyQueue();

        scriptFileWatcher.onDependencyChange(p.toString());

        awaitEmptyQueue();

        verify(scriptEngineManagerMock, never()).createScriptEngine("js", p.toString());
    }

    @Test
    public void testRemoveBeforeReAdd() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);
        updateStartLevel(100);

        Path p = getFile("script.js");

        InOrder inOrder = inOrder(scriptEngineManagerMock);
        String scriptIdentifier = ScriptFileReference.getScriptIdentifier(p);

        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p);

        awaitEmptyQueue();
        inOrder.verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", scriptIdentifier);

        scriptFileWatcher.processWatchEvent(null, ENTRY_MODIFY, p);

        awaitEmptyQueue();
        inOrder.verify(scriptEngineManagerMock, timeout(10000)).removeEngine(scriptIdentifier);
        inOrder.verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", scriptIdentifier);
    }

    @Test
    public void testDirectoryAdded() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);
        updateStartLevel(100);

        Path p1 = getFile("dir/script.js");
        Path p2 = getFile("dir/script2.js");
        Path d = p1.getParent();

        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, d);

        awaitEmptyQueue();

        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p1.toString());
        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p2.toString());
    }

    @Test
    public void testDirectoryAddedSubDirIncluded() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);
        updateStartLevel(100);

        Path p1 = getFile("dir/script.js");
        Path p2 = getFile("dir/sub/script.js");
        Path d = p1.getParent();

        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, d);

        awaitEmptyQueue();

        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js",
                ScriptFileReference.getScriptIdentifier(p1));
        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js",
                ScriptFileReference.getScriptIdentifier(p2));
    }

    @Test
    public void testDirectoryAddedSubDirIgnored() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);
        watchSubDirectories = false;
        updateStartLevel(100);

        Path p1 = getFile("dir/script.js");
        Path p2 = getFile("dir/sub/script.js");
        Path d = p1.getParent();

        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, d);

        awaitEmptyQueue();

        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js",
                ScriptFileReference.getScriptIdentifier(p1));
        verify(scriptEngineManagerMock, never()).createScriptEngine("js", ScriptFileReference.getScriptIdentifier(p2));
    }

    @Test
    public void testSortsAllFilesInNewDirectory() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);
        updateStartLevel(100);

        Path p20 = getFile("dir/script.sl20.js");
        Path p10 = getFile("dir/script2.sl10.js");
        Path d = p10.getParent();

        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, d);

        awaitEmptyQueue();

        InOrder inOrder = inOrder(scriptEngineManagerMock);
        inOrder.verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p10.toString());
        inOrder.verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p20.toString());
    }

    @Test
    public void testDirectoryRemoved() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(true);
        updateStartLevel(100);

        Path p1 = getFile("dir/script.js");
        Path p2 = getFile("dir/script2.js");
        Path d = p1.getParent();

        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p1);
        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p2);
        scriptFileWatcher.processWatchEvent(null, ENTRY_DELETE, d);

        awaitEmptyQueue();

        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p1.toString());
        verify(scriptEngineManagerMock, timeout(10000)).createScriptEngine("js", p2.toString());
        verify(scriptEngineManagerMock, timeout(10000)).removeEngine(p1.toString());
        verify(scriptEngineManagerMock, timeout(10000)).removeEngine(p2.toString());
    }

    @Test
    public void testScriptEngineRemovedOnFailedLoad() {
        when(scriptEngineManagerMock.isSupported("js")).thenReturn(true);
        ScriptEngineContainer scriptEngineContainer = mock(ScriptEngineContainer.class);
        when(scriptEngineContainer.getScriptEngine()).thenReturn(mock(ScriptEngine.class));
        when(scriptEngineManagerMock.createScriptEngine(anyString(), anyString())).thenReturn(scriptEngineContainer);
        when(scriptEngineManagerMock.loadScript(any(), any())).thenReturn(false);
        updateStartLevel(100);

        Path p = getFile("script.js");

        when(scriptEngineContainer.getIdentifier()).thenReturn(ScriptFileReference.getScriptIdentifier(p));

        scriptFileWatcher.processWatchEvent(null, ENTRY_CREATE, p);

        awaitEmptyQueue();

        InOrder inOrder = inOrder(scriptEngineManagerMock);
        inOrder.verify(scriptEngineManagerMock, timeout(1000)).createScriptEngine("js",
                ScriptFileReference.getScriptIdentifier(p));
        inOrder.verify(scriptEngineManagerMock, timeout(1000))
                .loadScript(eq(ScriptFileReference.getScriptIdentifier(p)), any());
        inOrder.verify(scriptEngineManagerMock, timeout(1000)).removeEngine(ScriptFileReference.getScriptIdentifier(p));
    }

    @Test
    public void testIfInitializedForEarlyInitialization() {
        CompletableFuture<?> initialized = scriptFileWatcher.ifInitialized();

        assertThat(initialized.isDone(), is(false));

        updateStartLevel(StartLevelService.STARTLEVEL_STATES);
        waitForAssert(() -> assertThat(initialized.isDone(), is(true)));
    }

    @Test
    public void testIfInitializedForLateInitialization() {
        when(startLevelServiceMock.getStartLevel()).thenReturn(StartLevelService.STARTLEVEL_RULEENGINE);
        AbstractScriptFileWatcher watcher = createScriptFileWatcher();

        waitForAssert(() -> assertThat(watcher.ifInitialized().isDone(), is(true)));

        watcher.deactivate();
    }

    private Path getFile(String name) {
        Path tempFile = tempScriptDir.resolve(name);
        try {
            File parent = tempFile.getParent().toFile();

            if (!parent.exists() && !parent.mkdirs()) {
                fail("Failed to create parent directories");
            }

            if (!tempFile.toFile().createNewFile()) {
                fail("Failed to create temp script file");
            }
        } catch (IOException e) {
            throw new AssertionFailedError("Failed to create temp script file: " + e.getMessage());
        }
        return Path.of(tempFile.toUri());
    }

    /**
     * Increase the start level in steps of 10
     *
     * @param level the target start-level
     */
    private void updateStartLevel(int level) {
        while (currentStartLevel < level) {
            currentStartLevel += 10;
            scriptFileWatcher.onReadyMarkerAdded(
                    new ReadyMarker(StartLevelService.STARTLEVEL_MARKER_TYPE, Integer.toString(currentStartLevel)));
        }
    }

    private AbstractScriptFileWatcher createScriptFileWatcher() {
        return new AbstractScriptFileWatcher(scriptEngineManagerMock, readyServiceMock, startLevelServiceMock, "") {

            @Override
            protected ScheduledExecutorService getScheduler() {
                return new CountingScheduledExecutor(atomicInteger);
            }

            @Override
            protected boolean watchSubDirectories() {
                return watchSubDirectories;
            }
        };
    }

    private void awaitEmptyQueue() {
        waitForAssert(() -> assertThat(atomicInteger.get(), is(0)));
    }

    private static class CountingScheduledExecutor extends ScheduledThreadPoolExecutor {

        private final AtomicInteger counter;

        public CountingScheduledExecutor(AtomicInteger counter) {
            super(1);

            this.counter = counter;
        }

        @Override
        public Future<?> submit(@NonNullByDefault({}) Runnable runnable) {
            counter.getAndIncrement();
            Runnable wrappedRunnable = () -> {
                runnable.run();
                counter.getAndDecrement();
            };
            return super.submit(wrappedRunnable);
        }
    }
}
