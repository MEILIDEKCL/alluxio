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

package alluxio.master;

import alluxio.dora.Constants;
import alluxio.dora.client.file.FileSystem;
import alluxio.dora.client.file.FileSystemContext;
import alluxio.dora.conf.Configuration;
import alluxio.dora.conf.PropertyKey;
import alluxio.dora.grpc.GrpcServerAddress;
import alluxio.dora.grpc.GrpcServerBuilder;
import alluxio.dora.master.AlluxioSimpleMasterProcess;
import alluxio.dora.master.MasterContext;
import alluxio.dora.master.PrimarySelector;
import alluxio.grpc.JournalDomain;
import alluxio.master.job.JobMaster;
import alluxio.dora.master.journal.DefaultJournalMaster;
import alluxio.dora.master.journal.JournalMaster;
import alluxio.dora.master.journal.JournalSystem;
import alluxio.dora.master.journal.JournalUtils;
import alluxio.dora.master.journal.raft.RaftJournalSystem;
import alluxio.dora.master.journal.ufs.UfsJournalSingleMasterPrimarySelector;
import alluxio.dora.master.service.rpc.RpcServerService;
import alluxio.dora.master.service.web.WebServerService;
import alluxio.underfs.JobUfsManager;
import alluxio.dora.underfs.UfsManager;
import alluxio.dora.util.CommonUtils.ProcessType;
import alluxio.dora.util.URIUtils;
import alluxio.dora.util.network.NetworkAddressUtils.ServiceType;
import alluxio.dora.web.JobMasterWebServer;
import alluxio.dora.web.WebServer;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

/**
 * This class is responsible for initializing the different masters that are configured to run.
 */
@NotThreadSafe
public class AlluxioJobMasterProcess extends AlluxioSimpleMasterProcess {
  private static final Logger LOG = LoggerFactory.getLogger(AlluxioJobMasterProcess.class);

  AlluxioJobMasterProcess(JournalSystem journalSystem, PrimarySelector leaderSelector) {
    super("job", JournalDomain.JOB_MASTER, journalSystem, leaderSelector,
        ServiceType.JOB_MASTER_WEB, ServiceType.JOB_MASTER_RPC, PropertyKey.JOB_MASTER_HOSTNAME);
    FileSystemContext fsContext = FileSystemContext.create(Configuration.global());
    FileSystem fileSystem = FileSystem.Factory.create(fsContext);
    UfsManager ufsManager = new JobUfsManager();
    try {
      MasterContext<UfsManager> context =
          new MasterContext<>(mJournalSystem, leaderSelector, null, ufsManager);
      // Create master.
      mRegistry.add(JobMaster.class, new JobMaster(context, fileSystem, fsContext, ufsManager));
      mRegistry.add(JournalMaster.class,
          new DefaultJournalMaster(JournalDomain.JOB_MASTER, context));
    } catch (Exception e) {
      LOG.error("Failed to create job master", e);
      throw new RuntimeException("Failed to create job master", e);
    }
  }

  /**
   * @return the {@link JobMaster} for this process
   */
  public JobMaster getJobMaster() {
    return mRegistry.get(JobMaster.class);
  }

  @Override
  public WebServer createWebServer() {
    return new JobMasterWebServer(ServiceType.JOB_MASTER_WEB.getServiceName(), mWebBindAddress,
              this);
  }

  @Override
  public GrpcServerBuilder createBaseRpcServer() {
    return GrpcServerBuilder
        .forAddress(GrpcServerAddress.create(mRpcConnectAddress.getHostName(), mRpcBindAddress),
            Configuration.global())
        .flowControlWindow(
            (int) Configuration.getBytes(PropertyKey.JOB_MASTER_NETWORK_FLOWCONTROL_WINDOW))
        .keepAliveTime(Configuration.getMs(PropertyKey.JOB_MASTER_NETWORK_KEEPALIVE_TIME_MS),
            TimeUnit.MILLISECONDS)
        .keepAliveTimeout(
            Configuration.getMs(PropertyKey.JOB_MASTER_NETWORK_KEEPALIVE_TIMEOUT_MS),
            TimeUnit.MILLISECONDS)
        .permitKeepAlive(
            Configuration.getMs(PropertyKey.JOB_MASTER_NETWORK_PERMIT_KEEPALIVE_TIME_MS),
            TimeUnit.MILLISECONDS)
        .maxInboundMessageSize((int) Configuration
            .getBytes(PropertyKey.JOB_MASTER_NETWORK_MAX_INBOUND_MESSAGE_SIZE));
  }

  /**
   * Factory for creating {@link AlluxioJobMasterProcess}.
   */
  @ThreadSafe
  static final class Factory {
    /**
     * @return a new instance of {@link AlluxioJobMasterProcess}
     */
    public static AlluxioJobMasterProcess create() {
      URI journalLocation = JournalUtils.getJournalLocation();
      JournalSystem journalSystem = new JournalSystem.Builder()
          .setLocation(URIUtils.appendPathOrDie(journalLocation, Constants.JOB_JOURNAL_NAME))
          .build(ProcessType.JOB_MASTER);
      final PrimarySelector primarySelector;
      if (Configuration.getBoolean(PropertyKey.ZOOKEEPER_ENABLED)) {
        Preconditions.checkState(!(journalSystem instanceof RaftJournalSystem),
            "Raft journal cannot be used with Zookeeper enabled");
        primarySelector = PrimarySelector.Factory.createZkJobPrimarySelector();
      } else if (journalSystem instanceof RaftJournalSystem) {
        primarySelector = ((RaftJournalSystem) journalSystem).getPrimarySelector();
      } else {
        primarySelector = new UfsJournalSingleMasterPrimarySelector();
      }
      AlluxioJobMasterProcess ajmp = new AlluxioJobMasterProcess(journalSystem, primarySelector);
      ajmp.registerService(
          RpcServerService.Factory.create(ajmp.getRpcBindAddress(), ajmp, ajmp.getRegistry()));
      ajmp.registerService(WebServerService.Factory.create(ajmp.getWebBindAddress(), ajmp));
      return ajmp;
    }

    private Factory() {} // prevent instantiation
  }
}
