/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.cli.command.metadatacache;

import alluxio.dora.AlluxioURI;
import alluxio.cli.command.AbstractFuseShellCommand;
import alluxio.dora.client.file.FileSystem;
import alluxio.dora.client.file.FileSystemUtils;
import alluxio.dora.client.file.MetadataCachingFileSystem;
import alluxio.dora.client.file.URIStatus;
import alluxio.dora.conf.AlluxioConfiguration;
import alluxio.dora.conf.PropertyKey;
import alluxio.dora.exception.runtime.InvalidArgumentRuntimeException;
import alluxio.dora.exception.status.InvalidArgumentException;

/**
 * Metadata cache sub command.
 */
public abstract class AbstractMetadataCacheSubCommand extends AbstractFuseShellCommand {

  /**
   * @param fileSystem   the file system the command takes effect on
   * @param conf the Alluxio configuration
   * @param commandName  the parent command name
   */
  public AbstractMetadataCacheSubCommand(FileSystem fileSystem,
      AlluxioConfiguration conf, String commandName) {
    super(fileSystem, conf, commandName);
  }

  @Override
  public URIStatus run(AlluxioURI path, String[] argv) throws InvalidArgumentException {
    if (!FileSystemUtils.metadataEnabled(mConf)) {
      throw new InvalidArgumentRuntimeException(String.format("%s command is "
              + "not supported when %s is zero", getCommandName(),
          PropertyKey.USER_METADATA_CACHE_MAX_SIZE.getName()));
    }
    return runSubCommand(path, argv, (MetadataCachingFileSystem) mFileSystem);
  }

  /**
   * Run the metadatacache subcommand.
   *
   * @return the result of running the command
   */
  protected abstract URIStatus runSubCommand(AlluxioURI path, String[] argv,
      MetadataCachingFileSystem fs);
}
