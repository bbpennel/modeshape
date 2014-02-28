/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.modeshape.connector.filesystem;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Workspace;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import javax.jcr.observation.EventListener;
import javax.jcr.observation.ObservationManager;
import org.junit.Before;
import org.junit.Test;
import org.modeshape.common.FixFor;
import org.modeshape.common.annotation.Immutable;
import org.modeshape.common.util.FileUtil;
import org.modeshape.common.util.IoUtil;
import org.modeshape.jcr.SingleUseAbstractTest;
import org.modeshape.jcr.api.Binary;
import org.modeshape.jcr.api.JcrTools;
import org.modeshape.jcr.api.Session;
import org.modeshape.jcr.api.federation.FederationManager;
import org.modeshape.jcr.value.binary.ExternalBinaryValue;

public class FileSystemConnectorTest extends SingleUseAbstractTest {

    protected static final String TEXT_CONTENT = "Some text content";

    private Node testRoot;
    private Projection readOnlyProjection;
    private Projection readOnlyProjectionWithExclusion;
    private Projection readOnlyProjectionWithInclusion;
    private Projection storeProjection;
    private Projection jsonProjection;
    private Projection legacyProjection;
    private Projection noneProjection;
    private Projection pagedProjection;
    private Projection largeFilesProjection;
    private Projection largeFilesProjectionDefault;
    private Projection monitoringProjection;
    private Projection[] projections;
    private JcrTools tools;

    @Before
    public void before() throws Exception {
        tools = new JcrTools();
        readOnlyProjection = new Projection("readonly-files", "target/federation/files-read");
        readOnlyProjectionWithExclusion = new Projection("readonly-files-with-exclusion",
                                                         "target/federation/files-read-exclusion");
        readOnlyProjectionWithInclusion = new Projection("readonly-files-with-inclusion",
                                                         "target/federation/files-read-inclusion");
        storeProjection = new Projection("mutable-files-store", "target/federation/files-store");
        jsonProjection = new Projection("mutable-files-json", "target/federation/files-json");
        legacyProjection = new Projection("mutable-files-legacy", "target/federation/files-legacy");
        noneProjection = new Projection("mutable-files-none", "target/federation/files-none");
        pagedProjection = new PagedProjection("paged-files", "target/federation/paged-files");
        largeFilesProjection = new LargeFilesProjection("large-files","target/federation/large-files");
        largeFilesProjectionDefault = new LargeFilesProjection("large-files-default","target/federation/large-files-default");
        monitoringProjection = new Projection("monitoring", "target/federation/monitoring");

        projections = new Projection[] { readOnlyProjection, readOnlyProjectionWithInclusion, readOnlyProjectionWithExclusion,
                                         storeProjection, jsonProjection, legacyProjection, noneProjection, pagedProjection,
                                         largeFilesProjection, largeFilesProjectionDefault, monitoringProjection };

        // Remove and then make the directory for our federation test ...
        for (Projection projection : projections) {
            projection.initialize();
        }

        startRepositoryWithConfiguration(getClass().getClassLoader()
                                                   .getResourceAsStream("config/repo-config-filesystem-federation.json"));
        registerNodeTypes("cnd/flex.cnd");

        Session session = (Session)jcrSession();
        testRoot = session.getRootNode().addNode("testRoot");
        testRoot.addNode("node1");
        session.save();

        readOnlyProjection.create(testRoot, "readonly");
        storeProjection.create(testRoot, "store");
        jsonProjection.create(testRoot, "json");
        legacyProjection.create(testRoot, "legacy");
        noneProjection.create(testRoot, "none");
        pagedProjection.create(testRoot, "pagedFiles");
        largeFilesProjection.create(testRoot,"largeFiles");
        largeFilesProjectionDefault.create(testRoot,"largeFilesDefault");
        monitoringProjection.create(testRoot, "monitoring");
    }

    @Test
    @FixFor( "MODE-2061" )
    public void largeFilesTest() throws Exception {
        largeFilesURIBased();
        largeFilesContentBased();
    }

    private void largeFilesURIBased() throws Exception {
        String childName = "largeFiles";
        Session session = (Session)testRoot.getSession();
        String path = testRoot.getPath() + "/" + childName;

        Node files = session.getNode(path);
        assertThat(files.getName(), is(childName));
        assertThat(files.getPrimaryNodeType().getName(), is("nt:folder"));
        long before = System.currentTimeMillis();
        Node node1 = session.getNode(path + "/large-file1.png");
        long after = System.currentTimeMillis();
        long elapsed = after-before;
        assertThat(node1.getName(),is("large-file1.png"));
        assertThat(node1.getPrimaryNodeType().getName(),is("nt:file"));

        before = System.currentTimeMillis();
        Node node1Content = node1.getNode("jcr:content");
        after = System.currentTimeMillis();
        elapsed = after-before;
        assertThat(node1Content.getName(),is("jcr:content"));
        assertThat(node1Content.getPrimaryNodeType().getName(),is("nt:resource"));

        Binary binary = (Binary) node1Content.getProperty("jcr:data").getBinary();
        before = System.currentTimeMillis();
        String dsChecksum = binary.getHexHash();
        after = System.currentTimeMillis();
        elapsed = after-before;

        before = System.currentTimeMillis();
        dsChecksum = binary.getHexHash();
        after = System.currentTimeMillis();
        elapsed = after-before;
    }

    public void largeFilesContentBased() throws Exception {
        String childName = "largeFilesDefault";
        Session session = (Session)testRoot.getSession();
        String path = testRoot.getPath() + "/" + childName;

        Node files = session.getNode(path);
        assertThat(files.getName(), is(childName));
        assertThat(files.getPrimaryNodeType().getName(), is("nt:folder"));
        long before = System.currentTimeMillis();
        Node node1 = session.getNode(path + "/large-file1.png");
        long after = System.currentTimeMillis();
        long elapsed = after-before;
        assertThat(node1.getName(),is("large-file1.png"));
        assertThat(node1.getPrimaryNodeType().getName(), is("nt:file"));

        before = System.currentTimeMillis();
        Node node1Content = node1.getNode("jcr:content");
        after = System.currentTimeMillis();
        elapsed = after-before;
        assertThat(node1Content.getName(),is("jcr:content"));
        assertThat(node1Content.getPrimaryNodeType().getName(), is("nt:resource"));

        Binary binary = (Binary) node1Content.getProperty("jcr:data").getBinary();
        before = System.currentTimeMillis();
        String dsChecksum = binary.getHexHash();
        after = System.currentTimeMillis();
        elapsed = after-before;

        before = System.currentTimeMillis();
        dsChecksum = binary.getHexHash();
        after = System.currentTimeMillis();
        elapsed = after-before;
    }

    @Test
    @FixFor( "MODE-1982" )
    public void shouldReadNodesInAllProjections() throws Exception {
        readOnlyProjection.testContent(testRoot, "readonly");
        storeProjection.testContent(testRoot, "store");
        jsonProjection.testContent(testRoot, "json");
        legacyProjection.testContent(testRoot, "legacy");
        noneProjection.testContent(testRoot, "none");
        pagedProjection.testContent(testRoot, "pagedFiles");
        largeFilesProjection.testContent(testRoot,"largeFiles");
        largeFilesProjectionDefault.testContent(testRoot,"largeFilesDefault");
    }

    @Test
    @FixFor( "MODE-1951" )
    public void shouldReadNodesInProjectionWithInclusionFilter() throws Exception {
        readOnlyProjectionWithInclusion.create(testRoot, "readonly-inclusion");

        assertNotNull(session.getNode("/testRoot/readonly-inclusion"));
        assertNotNull(session.getNode("/testRoot/readonly-inclusion/dir3"));
        assertNotNull(session.getNode("/testRoot/readonly-inclusion/dir3/simple.json"));
        assertNotNull(session.getNode("/testRoot/readonly-inclusion/dir3/simple.txt"));

        assertPathNotFound("/testRoot/readonly-inclusion/dir1");
        assertPathNotFound("/testRoot/readonly-inclusion/dir2");
    }

    @Test
    @FixFor( "MODE-1951" )
    public void shouldReadNodesInProjectionWithExclusionFilter() throws Exception {
        readOnlyProjectionWithExclusion.create(testRoot, "readonly-exclusion");

        assertNotNull(session.getNode("/testRoot/readonly-exclusion"));
        assertNotNull(session.getNode("/testRoot/readonly-exclusion/dir3"));
        assertNotNull(session.getNode("/testRoot/readonly-exclusion/dir1"));
        assertNotNull(session.getNode("/testRoot/readonly-exclusion/dir2"));
        assertPathNotFound("/testRoot/readonly-exclusion/dir3/simple.json");
        assertPathNotFound("/testRoot/readonly-exclusion/dir3/simple.txt");
    }

    @Test
    public void shouldReadNodesInReadOnlyProjection() throws Exception {
        readOnlyProjection.testContent(testRoot, "readonly");
    }

    @Test
    public void shouldNotAllowUpdatingNodesInReadOnlyProjection() throws Exception {
        Node file = session.getNode("/testRoot/readonly/dir3/simple.json");
        try {
            file.addMixin("flex:anyProperties");
            file.setProperty("extraProp", "extraValue");
            session.save();
            fail("failed to throw read-only exception");
        } catch (RepositoryException e) {
            // expected
        }
    }

    @Test
    public void shouldNotAllowRemovingNodesInReadOnlyProjection() throws Exception {
        Node file = session.getNode("/testRoot/readonly/dir3/simple.json");
        try {
            session.refresh(false);
            file.remove();
            session.save();
            fail("failed to throw read-only exception");
        } catch (RepositoryException e) {
            // expected
        }
    }

    @Test
    public void shouldAllowUpdatingNodesInWritableStoreBasedProjection() throws Exception {
        Node file = session.getNode("/testRoot/store/dir3/simple.json");
        file.addMixin("flex:anyProperties");
        file.setProperty("extraProp", "extraValue");
        session.save();
        assertNoSidecarFile(storeProjection, "dir3/simple.json.modeshape");
        Node file2 = session.getNode("/testRoot/store/dir3/simple.json");
        assertThat(file2.getProperty("extraProp").getString(), is("extraValue"));
    }

    @Test
    @FixFor( {"MODE-1971", "MODE-1976"} )
    public void shouldBeAbleToCopyExternalNodesInTheSameSource() throws Exception {
        ((Workspace)session.getWorkspace()).copy("/testRoot/store/dir3/simple.json", "/testRoot/store/dir3/simple2.json");
        Node file = session.getNode("/testRoot/store/dir3/simple2.json");
        assertNotNull(file);
        assertEquals("nt:file", file.getPrimaryNodeType().getName());

        ((Workspace)session.getWorkspace()).copy("/testRoot/store/dir3", "/testRoot/store/dir4");
        Node folder = session.getNode("/testRoot/store/dir4");
        assertNotNull(folder);
        assertEquals("nt:folder", folder.getPrimaryNodeType().getName());
    }

    @Test
    @FixFor( "MODE-1976" )
    public void shouldBeAbleToCopyExternalNodesIntoTheRepository() throws Exception {
        jcrSession().getRootNode().addNode("files");
        jcrSession().save();
        jcrSession().getWorkspace().copy("/testRoot/store/dir3/simple.json", "/files/simple.json");
        Node file = session.getNode("/files/simple.json");
        assertNotNull(file);
        assertEquals("nt:file", file.getPrimaryNodeType().getName());
    }

    @Test
    @FixFor( "MODE-1976" )
    public void shouldBeAbleToCopyFromRepositoryToExternalSource() throws Exception {
        jcrSession().getRootNode().addNode("files").addNode("dir", "nt:folder");
        jcrSession().save();
        jcrSession().getWorkspace().copy("/files/dir", "/testRoot/store/dir");
        Node dir = session.getNode("/testRoot/store/dir");
        assertNotNull(dir);
        assertEquals("nt:folder", dir.getPrimaryNodeType().getName());
    }

    @Test
    @FixFor( {"MODE-1971", "MODE-1977"} )
    public void shouldBeAbleToMoveExternalNodes() throws Exception {
        ((Workspace)session.getWorkspace()).move("/testRoot/store/dir3/simple.json", "/testRoot/store/dir3/simple2.json");
        Node file = session.getNode("/testRoot/store/dir3/simple2.json");
        assertNotNull(file);
        assertEquals("nt:file", file.getPrimaryNodeType().getName());

        ((Workspace)session.getWorkspace()).move("/testRoot/store/dir3", "/testRoot/store/dir4");
        Node folder = session.getNode("/testRoot/store/dir4");
        assertNotNull(folder);
        assertEquals("nt:folder", folder.getPrimaryNodeType().getName());
    }

    @Test
    public void shouldAllowUpdatingNodesInWritableJsonBasedProjection() throws Exception {
        Node file = session.getNode("/testRoot/json/dir3/simple.json");
        file.addMixin("flex:anyProperties");
        file.setProperty("extraProp", "extraValue");
        Node content = file.getNode("jcr:content");
        content.addMixin("flex:anyProperties");
        content.setProperty("extraProp2", "extraValue2");
        session.save();
        assertThat(file.getProperty("extraProp").getString(), is("extraValue"));
        assertThat(file.getProperty("jcr:content/extraProp2").getString(), is("extraValue2"));
        assertJsonSidecarFile(jsonProjection, "dir3/simple.json");
        assertJsonSidecarFile(jsonProjection, "dir3/simple.json");
        Node file2 = session.getNode("/testRoot/json/dir3/simple.json");
        assertThat(file2.getProperty("extraProp").getString(), is("extraValue"));
        try {
            // Make sure the sidecar file can't be seen via JCR ...
            session.getNode("/testRoot/json/dir3/simple.json.modeshape.json");
            fail("found sidecar file as JCR node");
        } catch (PathNotFoundException e) {
            // expected
        }
    }

    @Test
    public void shouldAllowUpdatingNodesInWritableLegacyBasedProjection() throws Exception {
        Node file = session.getNode("/testRoot/legacy/dir3/simple.json");
        file.addMixin("flex:anyProperties");
        file.setProperty("extraProp", "extraValue");
        session.save();
        assertLegacySidecarFile(legacyProjection, "dir3/simple.json");
        Node file2 = session.getNode("/testRoot/legacy/dir3/simple.json");
        assertThat(file2.getProperty("extraProp").getString(), is("extraValue"));
        try {
            // Make sure the sidecar file can't be seen via JCR ...
            session.getNode("/testRoot/json/dir3/simple.json.modeshape");
            fail("found sidecar file as JCR node");
        } catch (PathNotFoundException e) {
            // expected
        }
    }

    @Test
    @FixFor( "MODE-1882" )
    public void shouldAllowCreatingNodesInWritablStoreBasedProjection() throws Exception {
        String actualContent = "This is the content of the file.";
        tools.uploadFile(session, "/testRoot/store/dir3/newFile.txt", new ByteArrayInputStream(actualContent.getBytes()));
        session.save();

        // Make sure the file on the file system contains what we put in ...
        assertFileContains(storeProjection, "dir3/newFile.txt", actualContent.getBytes());

        // Make sure that we can re-read the binary content via JCR ...
        Node contentNode = session.getNode("/testRoot/store/dir3/newFile.txt/jcr:content");
        Binary value = (Binary)contentNode.getProperty("jcr:data").getBinary();
        assertBinaryContains(value, actualContent.getBytes());
    }

    @Test
    @FixFor( "MODE-1802" )
    public void shouldSupportRootProjection() throws Exception {
        // Clean up the folder that the test creates
        FileUtil.delete("target/classes/test");

        javax.jcr.Session session = session();
        Node root = session.getNode("/fs");
        assertNotNull(root);
        Node folder1 = root.addNode("test", "nt:folder");
        session.save();
        Node folder2 = root.getNode("test");
        assertThat(folder1.getIdentifier(), is(folder2.getIdentifier()));
    }

    @Test
    @FixFor( "MODE-1802" )
    public void shouldIgnoreNamespaces() throws Exception {
        // Clean up the folder that the test creates
        FileUtil.delete("target/classes/test");

        javax.jcr.Session session = session();
        session.setNamespacePrefix("ms_test", "http://www.modeshape.org/test/");
        Node root = session.getNode("/fs");
        assertNotNull(root);

        root.addNode("ms_test:test", "nt:folder");
        session.save();

        assertNotNull(root.getNode("test"));
    }

    @Test
    @FixFor( "MODE-2073" )
    public void shouldBeAbleToCopyExternalNodesWithBinaryValuesIntoTheRepository() throws Exception {
        javax.jcr.Binary externalBinary = jcrSession().getNode("/testRoot/store/dir3/simple.json/jcr:content").getProperty("jcr:data").getBinary();
        jcrSession().getRootNode().addNode("files");
        jcrSession().save();
        jcrSession().getWorkspace().copy("/testRoot/store/dir3/simple.json", "/files/simple.json");
        Node file = session.getNode("/files/simple.json");
        assertNotNull(file);
        assertEquals("nt:file", file.getPrimaryNodeType().getName());
        Property property = file.getNode("jcr:content").getProperty("jcr:data");
        assertNotNull(property);
        javax.jcr.Binary copiedBinary = property.getBinary();
        assertFalse(copiedBinary instanceof ExternalBinaryValue);
        assertArrayEquals(IoUtil.readBytes(externalBinary.getStream()), IoUtil.readBytes(copiedBinary.getStream()));
    }

    @Test
    @FixFor( "MODE-2040" )
    public void shouldReceiveFSNotificationsWhenCreatingFiles() throws Exception {
        File rootFolder = new File("target/federation/monitoring");
        assertTrue(rootFolder.exists() && rootFolder.isDirectory());

        ObservationManager observationManager = ((Workspace)session.getWorkspace()).getObservationManager();
        int expectedEventCount = 5;
        CountDownLatch latch = new CountDownLatch(expectedEventCount);
        FSListener listener = new FSListener(latch);
        observationManager.addEventListener(listener, Event.NODE_ADDED, null, true, null, null, false);
        Thread.sleep(300);
        addFile(rootFolder, "testfile1", "data/simple.json");
        Thread.sleep(300);
        addFile(rootFolder, "testfile2", "data/simple.json");
        File folder1 = new File(rootFolder, "folder1");
        assertTrue(folder1.mkdirs() && folder1.exists() && folder1.isDirectory());
        //wait a bit to make sure the new folder is being watched
        Thread.sleep(500);
        addFile(folder1, "testfile11", "data/simple.json");
        Thread.sleep(300);
        addFile(rootFolder, "dir1/testfile11", "data/simple.json");

        if (!latch.await(3, TimeUnit.SECONDS)) {
            fail("Events not received from connector");
        }

        Map<Integer, List<String>> receivedEvents = listener.getReceivedEventTypeAndPaths();
        assertFalse(receivedEvents.isEmpty());
        List<String> receivedPaths = receivedEvents.get(Event.NODE_ADDED);
        assertNotNull(receivedPaths);
        assertEquals(expectedEventCount, receivedPaths.size());
        //the root paths are defined in the monitoring projection
        assertTrue(receivedPaths.contains("/testRoot/monitoring/testfile1"));
        assertTrue(receivedPaths.contains("/testRoot/monitoring/testfile2"));
        assertTrue(receivedPaths.contains("/testRoot/monitoring/folder1"));
        assertTrue(receivedPaths.contains("/testRoot/monitoring/folder1/testfile11"));
        assertTrue(receivedPaths.contains("/testRoot/monitoring/dir1/testfile11"));
    }

    @Test
    @FixFor( "MODE-2040" )
    public void shouldReceiveFSNotificationsWhenChangingFileContent() throws Exception {
        File rootFolder = new File("target/federation/monitoring");
        assertTrue(rootFolder.exists() && rootFolder.isDirectory());

        ObservationManager observationManager = ((Workspace)session.getWorkspace()).getObservationManager();
        int expectedEventCount = 2;
        CountDownLatch latch = new CountDownLatch(expectedEventCount);
        FSListener listener = new FSListener(latch);
        observationManager.addEventListener(listener, Event.PROPERTY_CHANGED, null, true, null, null, false);

        File dir3 = new File(rootFolder, "dir3");
        assertTrue(dir3.exists() && dir3.isDirectory());
        addFile(dir3, "simple.txt", "data/simple.json");

        if (!latch.await(3, TimeUnit.SECONDS)) {
            fail("Events not received from connector");
        }

        Map<Integer, List<String>> receivedEvents = listener.getReceivedEventTypeAndPaths();
        assertFalse(receivedEvents.isEmpty());
        List<String> receivedPaths = receivedEvents.get(Event.PROPERTY_CHANGED);
        assertNotNull(receivedPaths);

        //don't assert the size because the count of MODIFIED events is OS dependent
        //assertEquals(expectedEventCount, receivedPaths.size());
        assertTrue(receivedPaths.contains("/testRoot/monitoring/dir3/simple.txt/jcr:content/jcr:lastModified"));
        assertTrue(receivedPaths.contains("/testRoot/monitoring/dir3/simple.txt/jcr:content/jcr:data"));
    }

    @Test
    @FixFor( "MODE-2040" )
    public void shouldReceiveFSNotificationsWhenRemovingFiles() throws Exception {
        File rootFolder = new File("target/federation/monitoring");
        assertTrue(rootFolder.exists() && rootFolder.isDirectory());

        ObservationManager observationManager = ((Workspace)session.getWorkspace()).getObservationManager();
        int expectedEventCount = 3;
        CountDownLatch latch = new CountDownLatch(expectedEventCount);
        FSListener listener = new FSListener(latch);
        observationManager.addEventListener(listener, Event.NODE_REMOVED, null, true, null, null, false);
        Thread.sleep(300);
        File dir3 = new File(rootFolder, "dir3");
        FileUtil.delete(new File(dir3, "simple.json"));
        Thread.sleep(300);
        FileUtil.delete(new File(dir3, "simple.txt"));
        Thread.sleep(300);
        FileUtil.delete(dir3);

        if (!latch.await(3, TimeUnit.SECONDS)) {
            fail("Events not received from connector");
        }

        Map<Integer, List<String>> receivedEvents = listener.getReceivedEventTypeAndPaths();
        assertFalse(receivedEvents.isEmpty());
        List<String> receivedPaths = receivedEvents.get(Event.NODE_REMOVED);
        assertNotNull(receivedPaths);
        assertEquals(expectedEventCount, receivedPaths.size());

        assertTrue(receivedPaths.contains("/testRoot/monitoring/dir3/simple.json"));
        assertTrue(receivedPaths.contains("/testRoot/monitoring/dir3/simple.txt"));
        assertTrue(receivedPaths.contains("/testRoot/monitoring/dir3"));
    }


    protected void assertNoSidecarFile( Projection projection,
                                        String filePath ) {
        assertThat(projection.getTestFile(filePath + JsonSidecarExtraPropertyStore.DEFAULT_EXTENSION).exists(), is(false));
        assertThat(projection.getTestFile(filePath + LegacySidecarExtraPropertyStore.DEFAULT_EXTENSION).exists(), is(false));
        assertThat(projection.getTestFile(filePath + JsonSidecarExtraPropertyStore.DEFAULT_RESOURCE_EXTENSION).exists(),
                   is(false));
        assertThat(projection.getTestFile(filePath + LegacySidecarExtraPropertyStore.DEFAULT_RESOURCE_EXTENSION).exists(),
                   is(false));
    }

    protected void assertJsonSidecarFile( Projection projection,
                                          String filePath ) {
        File sidecarFile = projection.getTestFile(filePath + JsonSidecarExtraPropertyStore.DEFAULT_EXTENSION);
        if (sidecarFile.exists()) return;
        sidecarFile = projection.getTestFile(filePath + JsonSidecarExtraPropertyStore.DEFAULT_RESOURCE_EXTENSION);
        assertThat(sidecarFile.exists(), is(true));
    }

    protected void assertFileContains( Projection projection,
                                       String filePath,
                                       InputStream expectedContent ) throws IOException {
        assertFileContains(projection, filePath, IoUtil.readBytes(expectedContent));
    }

    protected void assertFileContains( Projection projection,
                                       String filePath,
                                       byte[] expectedContent ) throws IOException {
        File contentFile = projection.getTestFile(filePath);
        assertThat(contentFile.exists(), is(true));
        byte[] actual = IoUtil.readBytes(contentFile);
        assertThat(actual, is(expectedContent));
    }

    protected void assertBinaryContains( Binary binaryValue,
                                         byte[] expectedContent ) throws IOException, RepositoryException {
        byte[] actual = IoUtil.readBytes(binaryValue.getStream());
        assertThat(actual, is(expectedContent));
    }

    protected void assertLegacySidecarFile( Projection projection,
                                            String filePath ) {
        File sidecarFile = projection.getTestFile(filePath + LegacySidecarExtraPropertyStore.DEFAULT_EXTENSION);
        if (sidecarFile.exists()) return;
        sidecarFile = projection.getTestFile(filePath + LegacySidecarExtraPropertyStore.DEFAULT_RESOURCE_EXTENSION);
        assertThat(sidecarFile.exists(), is(true));
    }

    protected void assertFolder( Node node,
                                 File dir ) throws RepositoryException {
        assertThat(dir.exists(), is(true));
        assertThat(dir.canRead(), is(true));
        assertThat(dir.isDirectory(), is(true));
        assertThat(node.getName(), is(dir.getName()));
        assertThat(node.getIndex(), is(1));
        assertThat(node.getPrimaryNodeType().getName(), is("nt:folder"));
        assertThat(node.getProperty("jcr:created").getLong(), is(dir.lastModified()));
    }

    protected void assertFile( Node node,
                               File file ) throws RepositoryException {
        long lastModified = file.lastModified();
        assertThat(node.getName(), is(file.getName()));
        assertThat(node.getIndex(), is(1));
        assertThat(node.getPrimaryNodeType().getName(), is("nt:file"));
        assertThat(node.getProperty("jcr:created").getLong(), is(lastModified));
        Node content = node.getNode("jcr:content");
        assertThat(content.getName(), is("jcr:content"));
        assertThat(content.getIndex(), is(1));
        assertThat(content.getPrimaryNodeType().getName(), is("nt:resource"));
        assertThat(content.getProperty("jcr:lastModified").getLong(), is(lastModified));
    }

    private void assertPathNotFound( String path ) throws Exception {
        try {
            session.getNode(path);
            fail(path + " was found, even though it shouldn't have been");
        } catch (PathNotFoundException e) {
            // expected
        }
    }

    private static void addFile( File directory,
                                 String path,
                                 String contentFile ) throws IOException {
        File file = new File(directory, path);
        IoUtil.write(FileSystemConnectorTest.class.getClassLoader().getResourceAsStream(contentFile), new FileOutputStream(file));
    }

    private class FSListener implements EventListener {

        private final CountDownLatch latch;
        private Map<Integer, List<String>> receivedEventTypeAndPaths;

        private FSListener( CountDownLatch latch ) {
            this.latch = latch;
            this.receivedEventTypeAndPaths = new HashMap<>();
        }

        @Override
        public void onEvent( EventIterator events ) {
            while (events.hasNext()) {
                try {
                    Event event = events.nextEvent();
                    int type = event.getType();

                    List<String> paths = receivedEventTypeAndPaths.get(type);
                    if (paths == null) {
                        paths = new ArrayList<>();
                        receivedEventTypeAndPaths.put(type, paths);
                    }

                    paths.add(event.getPath());
                    latch.countDown();
                } catch (RepositoryException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        private Map<Integer, List<String>> getReceivedEventTypeAndPaths() {
            return receivedEventTypeAndPaths;
        }
    }

    @Immutable
    protected class Projection {
        protected final File directory;
        private final String name;

        public Projection( String name,
                           String directoryPath ) {
            this.name = name;
            this.directory = new File(directoryPath);
        }

        public String getName() {
            return name;
        }

        public void create( Node parentNode,
                            String childName ) throws RepositoryException {
            Session session = (Session)parentNode.getSession();
            FederationManager fedMgr = session.getWorkspace().getFederationManager();
            fedMgr.createProjection(parentNode.getPath(), getName(), "/", childName);
        }

        public void initialize() throws IOException {
            if (directory.exists()) FileUtil.delete(directory);
            directory.mkdirs();
            // Make some content ...
            new File(directory, "dir1").mkdir();
            new File(directory, "dir2").mkdir();
            new File(directory, "dir3").mkdir();
            File simpleJson = new File(directory, "dir3/simple.json");
            IoUtil.write(getClass().getClassLoader().getResourceAsStream("data/simple.json"), new FileOutputStream(simpleJson));
            File simpleTxt = new File(directory, "dir3/simple.txt");
            IoUtil.write(TEXT_CONTENT, new FileOutputStream(simpleTxt));
        }

        public void delete() {
            if (directory.exists()) FileUtil.delete(directory);
        }

        public File getTestFile( String relativePath ) {
            return new File(directory, relativePath);
        }

        public void testContent( Node federatedNode,
                                 String childName ) throws RepositoryException {
            Session session = (Session)federatedNode.getSession();
            String path = federatedNode.getPath() + "/" + childName;

            Node files = session.getNode(path);
            assertThat(files.getName(), is(childName));
            assertThat(files.getPrimaryNodeType().getName(), is("nt:folder"));
            Node dir1 = session.getNode(path + "/dir1");
            Node dir2 = session.getNode(path + "/dir2");
            Node dir3 = session.getNode(path + "/dir3");
            Node simpleJson = session.getNode(path + "/dir3/simple.json");
            Node simpleText = session.getNode(path + "/dir3/simple.txt");
            assertFolder(dir1, getTestFile("dir1"));
            assertFolder(dir2, getTestFile("dir2"));
            assertFolder(dir3, getTestFile("dir3"));
            assertFile(simpleJson, getTestFile("dir3/simple.json"));
            assertFile(simpleText, getTestFile("dir3/simple.txt"));

            // Look up a node by identifier ...
            String externalNodeId = simpleJson.getIdentifier();
            Node simpleJson2 = session.getNodeByIdentifier(externalNodeId);
            assertFile(simpleJson2, getTestFile("dir3/simple.json"));

            // Look up the node again by path ...
            Node simpleJson3 = session.getNode(path + "/dir3/simple.json");
            assertFile(simpleJson3, getTestFile("dir3/simple.json"));

            // Look for a node that isn't there ...
            try {
                session.getNode(path + "/dir3/non-existant.oops");
                fail("Should not have been able to find a non-existing file");
            } catch (PathNotFoundException e) {
                // expected
            }
        }

        @Override
        public String toString() {
            return "Projection: " + name + " (at '" + directory.getAbsolutePath() + "')";
        }
    }

    protected class PagedProjection extends Projection {

        public PagedProjection( String name,
                                String directoryPath ) {
            super(name, directoryPath);
        }

        @Override
        public void testContent( Node federatedNode,
                                 String childName ) throws RepositoryException {
            Session session = (Session)federatedNode.getSession();
            String path = federatedNode.getPath() + "/" + childName;

            assertFolder(session, path, "dir1", "dir2", "dir3", "dir4", "dir5");
            assertFolder(session,
                         path + "/dir1",
                         "simple1.json",
                         "simple2.json",
                         "simple3.json",
                         "simple4.json",
                         "simple5.json",
                         "simple6.json");
            assertFolder(session, path + "/dir2", "simple1.json", "simple2.json");
            assertFolder(session, path + "/dir3", "simple1.json");
            assertFolder(session, path + "/dir4", "simple1.json", "simple2.json", "simple3.json");
            assertFolder(session, path + "/dir5", "simple1.json", "simple2.json", "simple3.json", "simple4.json", "simple5.json");
        }

        private void assertFolder( Session session,
                                   String path,
                                   String... childrenNames ) throws RepositoryException {
            Node folderNode = session.getNode(path);
            assertThat(folderNode.getPrimaryNodeType().getName(), is("nt:folder"));
            List<String> expectedChildren = new ArrayList<String>(Arrays.asList(childrenNames));

            NodeIterator nodes = folderNode.getNodes();
            assertEquals(expectedChildren.size(), nodes.getSize());
            while (nodes.hasNext()) {
                Node node = nodes.nextNode();
                String nodeName = node.getName();
                assertTrue(expectedChildren.contains(nodeName));
                expectedChildren.remove(nodeName);
            }
        }

        @Override
        public void initialize() throws IOException {
            if (directory.exists()) FileUtil.delete(directory);
            directory.mkdirs();
            // Make some content ...
            new File(directory, "dir1").mkdir();
            addFile(directory, "dir1/simple1.json", "data/simple.json");
            addFile(directory, "dir1/simple2.json", "data/simple.json");
            addFile(directory, "dir1/simple3.json", "data/simple.json");
            addFile(directory, "dir1/simple4.json", "data/simple.json");
            addFile(directory, "dir1/simple5.json", "data/simple.json");
            addFile(directory, "dir1/simple6.json", "data/simple.json");

            new File(directory, "dir2").mkdir();
            addFile(directory, "dir2/simple1.json", "data/simple.json");
            addFile(directory, "dir2/simple2.json", "data/simple.json");

            new File(directory, "dir3").mkdir();
            addFile(directory, "dir3/simple1.json", "data/simple.json");

            new File(directory, "dir4").mkdir();
            addFile(directory, "dir4/simple1.json", "data/simple.json");
            addFile(directory, "dir4/simple2.json", "data/simple.json");
            addFile(directory, "dir4/simple3.json", "data/simple.json");

            new File(directory, "dir5").mkdir();
            addFile(directory, "dir5/simple1.json", "data/simple.json");
            addFile(directory, "dir5/simple2.json", "data/simple.json");
            addFile(directory, "dir5/simple3.json", "data/simple.json");
            addFile(directory, "dir5/simple4.json", "data/simple.json");
            addFile(directory, "dir5/simple5.json", "data/simple.json");
        }
    }
    
    protected class LargeFilesProjection extends Projection {

        public LargeFilesProjection( String name,
                                     String directoryPath ) {
            super(name, directoryPath);
        }

        @Override
        public void testContent( Node federatedNode,
                                 String childName ) throws RepositoryException {
            Session session = (Session)federatedNode.getSession();
            String path = federatedNode.getPath() + "/" + childName;
            assertFolder(session, path, "large-file1.png");
        }

        private void assertFolder( Session session,
                                   String path,
                                   String... childrenNames ) throws RepositoryException {
            Node folderNode = session.getNode(path);
            assertThat(folderNode.getPrimaryNodeType().getName(), is("nt:folder"));
            List<String> expectedChildren = new ArrayList<String>(Arrays.asList(childrenNames));

            NodeIterator nodes = folderNode.getNodes();
            assertEquals(expectedChildren.size(), nodes.getSize());
            while (nodes.hasNext()) {
                Node node = nodes.nextNode();
                String nodeName = node.getName();
                assertTrue(expectedChildren.contains(nodeName));
                expectedChildren.remove(nodeName);
            }
        }

        @Override
        public void initialize() throws IOException {
            if (directory.exists()) FileUtil.delete(directory);
            directory.mkdirs();
            // Make some content ...
            addFile("large-file1.png", "data/large-file1.png");
        }

        private void addFile( String path,
                              String contentFile ) throws IOException {
            File file = new File(directory, path);
            IoUtil.write(getClass().getClassLoader().getResourceAsStream(contentFile), new FileOutputStream(file));
        }
    }
}
