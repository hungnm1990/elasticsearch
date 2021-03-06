/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.cli;

import com.google.common.base.Charsets;
import com.google.common.collect.Sets;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.*;
import java.util.Set;

import static org.hamcrest.Matchers.*;

/**
 *
 */
public class CheckFileCommandTests extends ElasticsearchTestCase {

    private CliToolTestCase.CaptureOutputTerminal captureOutputTerminal = new CliToolTestCase.CaptureOutputTerminal();

    private Configuration jimFsConfiguration = Configuration.unix().toBuilder().setAttributeViews("basic", "owner", "posix", "unix").build();
    private Configuration jimFsConfigurationWithoutPermissions = randomBoolean() ? Configuration.unix().toBuilder().setAttributeViews("basic").build() : Configuration.windows();

    private enum Mode {
        CHANGE, KEEP, DISABLED
    }

    @Test
    public void testThatCommandLogsErrorMessageOnFail() throws Exception {
        executeCommand(jimFsConfiguration, new PermissionCheckFileCommand(captureOutputTerminal, Mode.CHANGE));
        assertThat(captureOutputTerminal.getTerminalOutput(), hasItem(containsString("Please ensure that the user account running Elasticsearch has read access to this file")));
    }

    @Test
    public void testThatCommandLogsNothingWhenPermissionRemains() throws Exception {
        executeCommand(jimFsConfiguration, new PermissionCheckFileCommand(captureOutputTerminal, Mode.KEEP));
        assertThat(captureOutputTerminal.getTerminalOutput(), hasSize(0));
    }

    @Test
    public void testThatCommandLogsNothingWhenDisabled() throws Exception {
        executeCommand(jimFsConfiguration, new PermissionCheckFileCommand(captureOutputTerminal, Mode.DISABLED));
        assertThat(captureOutputTerminal.getTerminalOutput(), hasSize(0));
    }

    @Test
    public void testThatCommandLogsNothingIfFilesystemDoesNotSupportPermissions() throws Exception {
        executeCommand(jimFsConfigurationWithoutPermissions, new PermissionCheckFileCommand(captureOutputTerminal, Mode.DISABLED));
        assertThat(captureOutputTerminal.getTerminalOutput(), hasSize(0));
    }

    @Test
    public void testThatCommandLogsOwnerChange() throws Exception {
        executeCommand(jimFsConfiguration, new OwnerCheckFileCommand(captureOutputTerminal, Mode.CHANGE));
        assertThat(captureOutputTerminal.getTerminalOutput(), hasItem(allOf(containsString("Owner of file ["), containsString("] used to be ["), containsString("], but now is ["))));
    }

    @Test
    public void testThatCommandLogsNothingIfOwnerRemainsSame() throws Exception {
        executeCommand(jimFsConfiguration, new OwnerCheckFileCommand(captureOutputTerminal, Mode.KEEP));
        assertThat(captureOutputTerminal.getTerminalOutput(), hasSize(0));
    }

    @Test
    public void testThatCommandLogsNothingIfOwnerIsDisabled() throws Exception {
        executeCommand(jimFsConfiguration, new OwnerCheckFileCommand(captureOutputTerminal, Mode.DISABLED));
        assertThat(captureOutputTerminal.getTerminalOutput(), hasSize(0));
    }

    @Test
    public void testThatCommandLogsNothingIfFileSystemDoesNotSupportOwners() throws Exception {
        executeCommand(jimFsConfigurationWithoutPermissions, new OwnerCheckFileCommand(captureOutputTerminal, Mode.DISABLED));
        assertThat(captureOutputTerminal.getTerminalOutput(), hasSize(0));
    }

    @Test
    public void testThatCommandLogsIfGroupChanges() throws Exception {
        executeCommand(jimFsConfiguration, new GroupCheckFileCommand(captureOutputTerminal, Mode.CHANGE));
        assertThat(captureOutputTerminal.getTerminalOutput(), hasItem(allOf(containsString("Group of file ["), containsString("] used to be ["), containsString("], but now is ["))));
    }

    @Test
    public void testThatCommandLogsNothingIfGroupRemainsSame() throws Exception {
        executeCommand(jimFsConfiguration, new GroupCheckFileCommand(captureOutputTerminal, Mode.KEEP));
        assertThat(captureOutputTerminal.getTerminalOutput(), hasSize(0));
    }

    @Test
    public void testThatCommandLogsNothingIfGroupIsDisabled() throws Exception {
        executeCommand(jimFsConfiguration, new GroupCheckFileCommand(captureOutputTerminal, Mode.DISABLED));
        assertThat(captureOutputTerminal.getTerminalOutput(), hasSize(0));
    }

    @Test
    public void testThatCommandLogsNothingIfFileSystemDoesNotSupportGroups() throws Exception {
        executeCommand(jimFsConfigurationWithoutPermissions, new GroupCheckFileCommand(captureOutputTerminal, Mode.DISABLED));
        assertThat(captureOutputTerminal.getTerminalOutput(), hasSize(0));
    }

    @Test
    public void testThatCommandDoesNotLogAnythingOnFileCreation() throws Exception {
        Configuration configuration = randomBoolean() ? jimFsConfiguration : jimFsConfigurationWithoutPermissions;

        try (FileSystem fs = Jimfs.newFileSystem(configuration)) {
            Path path = fs.getPath(randomAsciiOfLength(10));
            new CreateFileCommand(captureOutputTerminal, path).execute(ImmutableSettings.EMPTY, new Environment(ImmutableSettings.EMPTY));
            assertThat(Files.exists(path), is(true));
        }

        assertThat(captureOutputTerminal.getTerminalOutput(), hasSize(0));
    }

    @Test
    public void testThatCommandWorksIfFileIsDeletedByCommand() throws Exception {
        Configuration configuration = randomBoolean() ? jimFsConfiguration : jimFsConfigurationWithoutPermissions;

        try (FileSystem fs = Jimfs.newFileSystem(configuration)) {
            Path path = fs.getPath(randomAsciiOfLength(10));
            Files.write(path, "anything".getBytes(Charsets.UTF_8));

            new DeleteFileCommand(captureOutputTerminal, path).execute(ImmutableSettings.EMPTY, new Environment(ImmutableSettings.EMPTY));
            assertThat(Files.exists(path), is(false));
        }

        assertThat(captureOutputTerminal.getTerminalOutput(), hasSize(0));
    }

    private void executeCommand(Configuration configuration, AbstractTestCheckFileCommand command) throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(configuration)) {
            command.execute(fs);
        }
    }

    abstract class AbstractTestCheckFileCommand extends CheckFileCommand {

        protected final Mode mode;
        protected FileSystem fs;
        protected Path[] paths;

        public AbstractTestCheckFileCommand(Terminal terminal, Mode mode) throws IOException {
            super(terminal);
            this.mode = mode;
        }

        public CliTool.ExitStatus execute(FileSystem fs) throws Exception {
            this.fs = fs;
            this.paths = new Path[] { writePath(fs, "p1", "anything"), writePath(fs, "p2", "anything"), writePath(fs, "p3", "anything") };
            return super.execute(ImmutableSettings.EMPTY, new Environment(ImmutableSettings.EMPTY));
        }

        private Path writePath(FileSystem fs, String name, String content) throws IOException {
            Path path = fs.getPath(name);
            Files.write(path, content.getBytes(Charsets.UTF_8));
            return path;
        }

        @Override
        protected Path[] pathsForPermissionsCheck(Settings settings, Environment env) {
            return paths;
        }
    }

    /**
     * command that changes permissions from a file if enabled
     */
    class PermissionCheckFileCommand extends AbstractTestCheckFileCommand {

        public PermissionCheckFileCommand(Terminal terminal, Mode mode) throws IOException {
            super(terminal, mode);
        }

        @Override
        public CliTool.ExitStatus doExecute(Settings settings, Environment env) throws Exception {
            int randomInt = randomInt(paths.length - 1);
            Path randomPath = paths[randomInt];
            switch (mode) {
                case CHANGE:
                    Files.write(randomPath, randomAsciiOfLength(10).getBytes(Charsets.UTF_8));
                    Files.setPosixFilePermissions(randomPath, Sets.newHashSet(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.GROUP_EXECUTE));
                    break;
                case KEEP:
                    Files.write(randomPath, randomAsciiOfLength(10).getBytes(Charsets.UTF_8));
                    Set<PosixFilePermission> posixFilePermissions = Files.getPosixFilePermissions(randomPath);
                    Files.setPosixFilePermissions(randomPath, posixFilePermissions);
                    break;
            }
            return CliTool.ExitStatus.OK;
        }

    }

    /**
     * command that changes the owner of a file if enabled
     */
    class OwnerCheckFileCommand extends AbstractTestCheckFileCommand {

        public OwnerCheckFileCommand(Terminal terminal, Mode mode) throws IOException {
            super(terminal, mode);
        }

        @Override
        public CliTool.ExitStatus doExecute(Settings settings, Environment env) throws Exception {
            int randomInt = randomInt(paths.length - 1);
            Path randomPath = paths[randomInt];
            switch (mode) {
                case CHANGE:
                    Files.write(randomPath, randomAsciiOfLength(10).getBytes(Charsets.UTF_8));
                    UserPrincipal randomOwner = fs.getUserPrincipalLookupService().lookupPrincipalByName(randomAsciiOfLength(10));
                    Files.setOwner(randomPath, randomOwner);
                    break;
                case KEEP:
                    Files.write(randomPath, randomAsciiOfLength(10).getBytes(Charsets.UTF_8));
                    UserPrincipal originalOwner = Files.getOwner(randomPath);
                    Files.setOwner(randomPath, originalOwner);
                    break;
            }

            return CliTool.ExitStatus.OK;
        }
    }

    /**
     * command that changes the group of a file if enabled
     */
    class GroupCheckFileCommand extends AbstractTestCheckFileCommand {

        public GroupCheckFileCommand(Terminal terminal, Mode mode) throws IOException {
            super(terminal, mode);
        }

        @Override
        public CliTool.ExitStatus doExecute(Settings settings, Environment env) throws Exception {
            int randomInt = randomInt(paths.length - 1);
            Path randomPath = paths[randomInt];
            switch (mode) {
                case CHANGE:
                    Files.write(randomPath, randomAsciiOfLength(10).getBytes(Charsets.UTF_8));
                    GroupPrincipal randomPrincipal = fs.getUserPrincipalLookupService().lookupPrincipalByGroupName(randomAsciiOfLength(10));
                    Files.getFileAttributeView(randomPath, PosixFileAttributeView.class).setGroup(randomPrincipal);
                    break;
                case KEEP:
                    Files.write(randomPath, randomAsciiOfLength(10).getBytes(Charsets.UTF_8));
                    GroupPrincipal groupPrincipal = Files.readAttributes(randomPath, PosixFileAttributes.class).group();
                    Files.getFileAttributeView(randomPath, PosixFileAttributeView.class).setGroup(groupPrincipal);
                    break;
            }

            return CliTool.ExitStatus.OK;
        }
    }

    /**
     * A command that creates a non existing file
     */
    class CreateFileCommand extends CheckFileCommand {

        private final Path pathToCreate;

        public CreateFileCommand(Terminal terminal, Path pathToCreate) {
            super(terminal);
            this.pathToCreate = pathToCreate;
        }

        @Override
        public CliTool.ExitStatus doExecute(Settings settings, Environment env) throws Exception {
            Files.write(pathToCreate, "anything".getBytes(Charsets.UTF_8));
            return CliTool.ExitStatus.OK;
        }

        @Override
        protected Path[] pathsForPermissionsCheck(Settings settings, Environment env) throws Exception {
            return new Path[] { pathToCreate };
        }
    }

    /**
     * A command that deletes an existing file
     */
    class DeleteFileCommand extends CheckFileCommand {

        private final Path pathToDelete;

        public DeleteFileCommand(Terminal terminal, Path pathToDelete) {
            super(terminal);
            this.pathToDelete = pathToDelete;
        }

        @Override
        public CliTool.ExitStatus doExecute(Settings settings, Environment env) throws Exception {
            Files.delete(pathToDelete);
            return CliTool.ExitStatus.OK;
        }

        @Override
        protected Path[] pathsForPermissionsCheck(Settings settings, Environment env) throws Exception {
            return new Path[] {pathToDelete};
        }
    }
}
