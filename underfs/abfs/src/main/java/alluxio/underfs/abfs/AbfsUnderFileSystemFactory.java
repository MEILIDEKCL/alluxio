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

package alluxio.underfs.abfs;

import alluxio.dora.AlluxioURI;
import alluxio.dora.Constants;
import alluxio.dora.underfs.UnderFileSystem;
import alluxio.dora.underfs.UnderFileSystemConfiguration;
import alluxio.dora.underfs.UnderFileSystemFactory;

import com.google.common.base.Preconditions;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Factory for creating {@link AbfsUnderFileSystem}.
 */
@ThreadSafe
public class AbfsUnderFileSystemFactory implements UnderFileSystemFactory {

  /**
   * Constructs a new {@link AbfsUnderFileSystemFactory}.
   */
  public AbfsUnderFileSystemFactory() {}

  @Override
  public UnderFileSystem create(String path, UnderFileSystemConfiguration conf) {
    Preconditions.checkArgument(path != null, "path may not be null");
    return AbfsUnderFileSystem.createInstance(new AlluxioURI(path), conf);
  }

  @Override
  public boolean supportsPath(String path) {
    return path != null
        && (path.startsWith(Constants.HEADER_ABFS)
        || path.startsWith(Constants.HEADER_ABFSS));
  }
}
