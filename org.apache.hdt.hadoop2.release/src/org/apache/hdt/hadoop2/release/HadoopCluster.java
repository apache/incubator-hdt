/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hdt.hadoop2.release;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobID;
import org.apache.hadoop.mapred.JobStatus;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hdt.core.Activator;
import org.apache.hdt.core.HadoopVersion;
import org.apache.hdt.core.launch.AbstractHadoopCluster;
import org.apache.hdt.core.launch.ConfProp;
import org.apache.hdt.core.launch.IHadoopJob;
import org.apache.hdt.core.launch.IJarModule;
import org.apache.hdt.core.launch.IJobListener;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

/**
 * Representation of a Hadoop location, meaning of the master node (NameNode,
 * JobTracker).
 * 
 * <p>
 * This class does not create any SSH connection anymore. Tunneling must be
 * setup outside of Eclipse for now (using Putty or <tt>ssh -D&lt;port&gt;
 * &lt;host&gt;</tt>)
 * 
 * <p>
 * <em> TODO </em>
 * <li>Disable the updater if a location becomes unreachable or fails for tool
 * long
 * <li>Stop the updater on location's disposal/removal
 */

public class HadoopCluster extends AbstractHadoopCluster {
	private ExecutorService service= Executors.newSingleThreadExecutor();

	/**
	 * Frequency of location status observations expressed as the delay in ms
	 * between each observation
	 * 
	 * TODO Add a preference parameter for this
	 */
	protected static final long STATUS_OBSERVATION_DELAY = 1500;

	/**
   * 
   */
	public class LocationStatusUpdater extends Job {

		JobClient client = null;

		/**
		 * Setup the updater
		 */
		public LocationStatusUpdater() {
			super("Map/Reduce location status updater");
			this.setSystem(true);
		}

		/* @inheritDoc */
		@Override
		protected IStatus run(IProgressMonitor monitor) {
			if (client == null) {
				try {
					client = HadoopCluster.this.getJobClient();

				} catch (IOException ioe) {
					client = null;
					return new Status(Status.ERROR, Activator.BUNDLE_ID, 0, "Cannot connect to the Map/Reduce location: "
							+ HadoopCluster.this.getLocationName(), ioe);
				}
			}
			Thread current = Thread.currentThread();
			ClassLoader oldLoader = current.getContextClassLoader();
			try {
			        current.setContextClassLoader(HadoopCluster.class.getClassLoader());
				// Set of all known existing Job IDs we want fresh info of
				Set<JobID> missingJobIds = new HashSet<JobID>(runningJobs.keySet());

				JobStatus[] jstatus = client.jobsToComplete();
				jstatus = jstatus == null ? new JobStatus[0] : jstatus;
				for (JobStatus status : jstatus) {

					JobID jobId = status.getJobID();
					missingJobIds.remove(jobId);

					HadoopJob hJob;
					synchronized (HadoopCluster.this.runningJobs) {
						hJob = runningJobs.get(jobId);
						if (hJob == null) {
							// Unknown job, create an entry
							RunningJob running = client.getJob(jobId);
							hJob = new HadoopJob(HadoopCluster.this, jobId, running, status);
							newJob(hJob);
						}
					}

					// Update HadoopJob with fresh infos
					updateJob(hJob, status);
				}

				// Ask explicitly for fresh info for these Job IDs
				for (JobID jobId : missingJobIds) {
					HadoopJob hJob = runningJobs.get(jobId);
					if (!hJob.isCompleted())
						updateJob(hJob, null);
				}

			} catch (IOException ioe) {
				client = null;
				return new Status(Status.ERROR, Activator.BUNDLE_ID, 0, "Cannot retrieve running Jobs on location: " + HadoopCluster.this.getLocationName(),
						ioe);
			} finally {
                            current.setContextClassLoader(oldLoader);
                         }


			// Schedule the next observation
			schedule(STATUS_OBSERVATION_DELAY);

			return Status.OK_STATUS;
		}

		/**
		 * Stores and make the new job available
		 * 
		 * @param data
		 */
		private void newJob(final HadoopJob data) {
			runningJobs.put(data.jobId, data);

			Display.getDefault().asyncExec(new Runnable() {
				public void run() {
					fireJobAdded(data);
				}
			});
		}

		/**
		 * Updates the status of a job
		 * 
		 * @param job
		 *            the job to update
		 */
		private void updateJob(final HadoopJob job, JobStatus status) {
			job.update(status);

			Display.getDefault().asyncExec(new Runnable() {
				public void run() {
					fireJobChanged(job);
				}
			});
		}

	}

	static Logger log = Logger.getLogger(HadoopCluster.class.getName());

	/**
	 * Hadoop configuration of the location. Also contains specific parameters
	 * for the plug-in. These parameters are prefix with eclipse.plug-in.*
	 */
	private Configuration conf;

	/**
	 * Jobs listeners
	 */
	private Set<IJobListener> jobListeners = new HashSet<IJobListener>();

	/**
	 * Jobs running on this location. The keys of this map are the Job IDs.
	 */
	private transient Map<JobID, HadoopJob> runningJobs = Collections.synchronizedMap(new TreeMap<JobID, HadoopJob>());

	/**
	 * Status updater for this location
	 */
	private LocationStatusUpdater statusUpdater;

	// state and status - transient
	private transient String state = "";

	/**
	 * Creates a new default Hadoop location
	 */
        public HadoopCluster() {
            this.conf = new Configuration();
            this.addPluginConfigDefaultProperties();
            conf.set("mapreduce.framework.name", "yarn");
        }
   
	/**
	 * Create a new Hadoop location by copying an already existing one.
	 * 
	 * @param source
	 *            the location to copy
	 */
	public HadoopCluster(HadoopCluster existing) {
		this();
		this.load(existing);
	}

	public void addJobListener(IJobListener l) {
		jobListeners.add(l);
	}

	public void dispose() {
		// TODO close DFS connections?
	}

	/**
	 * List all elements that should be present in the Server window (all
	 * servers and all jobs running on each servers)
	 * 
	 * @return collection of jobs for this location
	 */
	public Collection<? extends IHadoopJob> getJobs() {
		startStatusUpdater();
		return this.runningJobs.values();
	}

	/**
	 * Remove the given job from the currently running jobs map
	 * 
	 * @param job
	 *            the job to remove
	 */
	public void purgeJob(final IHadoopJob job) {
		runningJobs.remove(job.getJobID());
		Display.getDefault().asyncExec(new Runnable() {
			public void run() {
				fireJobRemoved(job);
			}
		});
	}

	/**
	 * Returns the {@link Configuration} defining this location.
	 * 
	 * @return the location configuration
	 */
	public Iterator<Entry<String, String>> getConfiguration() {
		return this.conf.iterator();
	}

	/**
	 * @return the conf
	 */
	public Configuration getConf() {
		return conf;
	}

	/**
	 * Gets a Hadoop configuration property value
	 * 
	 * @param prop
	 *            the configuration property
	 * @return the property value
	 */
	public String getConfPropValue(ConfProp prop) {
		String confPropName = getConfPropName(prop);
		return conf.get(confPropName);
	}

	/**
	 * Gets a Hadoop configuration property value
	 * 
	 * @param propName
	 *            the property name
	 * @return the property value
	 */
	public String getConfPropValue(String propName) {
		return this.conf.get(propName);
	}

	public String getLocationName() {
		return getConfPropValue(ConfProp.PI_LOCATION_NAME);
	}

	/**
	 * Returns the master host name of the Hadoop location (the Job tracker)
	 * 
	 * @return the host name of the Job tracker
	 */
	public String getMasterHostName() {
		return getConfPropValue(ConfProp.PI_RESOURCE_MGR_HOST);
	}

	public String getState() {
		return state;
	}

	/**
	 * Overwrite this location with the given existing location
	 * 
	 * @param existing
	 *            the existing location
	 */
	public void load(AbstractHadoopCluster existing) {
		this.conf = new Configuration(((HadoopCluster) existing).conf);
	}

        protected boolean loadConfiguration(Map<String, String> configuration) {
            Configuration newConf = new Configuration(this.conf);
            if (configuration == null)
                return false;
            for (Entry<String, String> entry : configuration.entrySet()) {
                newConf.set(entry.getKey(), entry.getValue());
            }
    
    
            this.conf = newConf;
            return true;
        }

	/**
	 * Sets a Hadoop configuration property value
	 * 
	 * @param prop
	 *            the property
	 * @param propvalue
	 *            the property value
	 */
	public void setConfPropValue(ConfProp prop, String propValue) {
            if (propValue != null)
                    setConfPropValue(getConfPropName(prop), propValue);
        }
    
        @Override
        public void setConfPropValue(String propName, String propValue) {
                conf.set(propName, propValue);
        }

	public void setLocationName(String newName) {
		setConfPropValue(ConfProp.PI_LOCATION_NAME, newName);
	}
    
	/**
	 * Write this location settings to the given output stream
	 * 
	 * @param out
	 *            the output stream
	 * @throws IOException
	 */
	public void storeSettingsToFile(File file) throws IOException {
		FileOutputStream fos = new FileOutputStream(file);
		try {
			this.conf.writeXml(fos);
			fos.close();
			fos = null;
		} finally {
			IOUtils.closeStream(fos);
		}

	}

	/* @inheritDoc */
	@Override
	public String toString() {
		return this.getLocationName();
	}

	/**
	 * Fill the configuration with valid default values
	 */
	private void addPluginConfigDefaultProperties() {
		for (ConfProp prop : ConfProp.values()) {
			conf.set(getConfPropName(prop), prop.defVal);
		}
	}

	/**
	 * Starts the location status updater
	 */
	private synchronized void startStatusUpdater() {
		if (statusUpdater == null) {
			statusUpdater = new LocationStatusUpdater();
			statusUpdater.schedule();
		}
	}

	/*
	 * Rewrite of the connecting and tunneling to the Hadoop location
	 */

	/**
	 * Provides access to the default file system of this location.
	 * 
	 * @return a {@link FileSystem}
	 */
	public FileSystem getDFS() throws IOException {
		return FileSystem.get(this.conf);
	}

	/**
	 * Provides access to the Job tracking system of this location
	 * 
	 * @return a {@link JobClient}
	 */
	public JobClient getJobClient() throws IOException {
		JobConf jconf = new JobConf(this.conf);
		return new JobClient(jconf);
	}

	/*
	 * Listeners handling
	 */

	protected void fireJarPublishDone(IJarModule jar) {
		for (IJobListener listener : jobListeners) {
			listener.publishDone(jar);
		}
	}

	protected void fireJarPublishStart(IJarModule jar) {
		for (IJobListener listener : jobListeners) {
			listener.publishStart(jar);
		}
	}

	protected void fireJobAdded(HadoopJob job) {
		for (IJobListener listener : jobListeners) {
			listener.jobAdded(job);
		}
	}

	protected void fireJobRemoved(IHadoopJob job) {
		for (IJobListener listener : jobListeners) {
			listener.jobRemoved(job);
		}
	}

	protected void fireJobChanged(HadoopJob job) {
		for (IJobListener listener : jobListeners) {
			listener.jobChanged(job);
		}
	}

	@Override
	public void saveConfiguration(File confDir, String jarFilePath) throws IOException {
		// Prepare the Hadoop configuration
		JobConf conf = new JobConf(this.conf);
		conf.setJar(jarFilePath);
		// Write it to the disk file
		File coreSiteFile = new File(confDir, "core-site.xml");
		File mapredSiteFile = new File(confDir, "yarn-site.xml");
		FileOutputStream fos = new FileOutputStream(coreSiteFile);
		FileInputStream fis = null;
		try {
			conf.writeXml(fos);
			fos.close();
			fos = new FileOutputStream(mapredSiteFile);
			fis = new FileInputStream(coreSiteFile);
			IOUtils.copyBytes(new BufferedInputStream(fis), fos, 4096);
		} finally {
			IOUtils.closeStream(fos);
			IOUtils.closeStream(fis);
		}

	}

	/* (non-Javadoc)
	 * @see org.apache.hdt.core.launch.AbstractHadoopCluster#isAvailable()
	 */
	@Override
	public boolean isAvailable() throws CoreException {
		Callable<JobClient> task= new Callable<JobClient>() {
			@Override
			public JobClient call() throws Exception {
			    return getJobClient();}}; 
		Future<JobClient> jobClientFuture = service.submit(task);
		try{
			jobClientFuture.get(500, TimeUnit.SECONDS);
			return true;
		}catch(Exception e){
			e.printStackTrace();
			throw new CoreException(new Status(Status.ERROR, 
					Activator.BUNDLE_ID, "unable to connect to server", e));
		}
	}

    @Override
    public HadoopVersion getVersion() {
            return HadoopVersion.Version2;
    }


    @Override
    public HadoopConfigurationBuilder getUIConfigurationBuilder() {
        return new HadoopV2ConfigurationBuilder(this);
    }
}
