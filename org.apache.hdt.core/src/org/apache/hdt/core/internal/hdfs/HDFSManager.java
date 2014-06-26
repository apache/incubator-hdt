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

package org.apache.hdt.core.internal.hdfs;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hdt.core.Activator;
import org.apache.hdt.core.hdfs.HDFSClient;
import org.apache.hdt.core.internal.HadoopManager;
import org.apache.hdt.core.internal.model.HDFSServer;
import org.apache.hdt.core.internal.model.HadoopFactory;
import org.apache.hdt.core.internal.model.ServerStatus;
import org.apache.log4j.Logger;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.EList;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.team.core.RepositoryProvider;

/**
 * Manages workspace files with server files.
 * 
 * @author Srimanth Gunturi
 * 
 */
public class HDFSManager {

	public static HDFSManager INSTANCE = new HDFSManager();
	private static final Logger logger = Logger.getLogger(HDFSManager.class);

	public static void disconnectProject(IProject project) {
		HDFSServer server = HDFSManager.INSTANCE.getServer(project.getLocationURI().toString());
		if (server != null && server.getStatusCode() != ServerStatus.DISCONNECTED_VALUE)
			server.setStatusCode(ServerStatus.DISCONNECTED_VALUE);
		try {
			project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		} catch (CoreException e) {
			logger.warn(e.getMessage(), e);
		}
	}

	public static void reconnectProject(IProject project) {
		HDFSServer server = HDFSManager.INSTANCE.getServer(project.getLocationURI().toString());
		if (server != null && server.getStatusCode() == ServerStatus.DISCONNECTED_VALUE)
			server.setStatusCode(0);
		try {
			project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		} catch (CoreException e) {
			logger.warn(e.getMessage(), e);
		}
	}

	private Map<HDFSServer, String> serverToProjectMap = new HashMap<HDFSServer, String>();
	private Map<String, HDFSServer> projectToServerMap = new HashMap<String, HDFSServer>();
	private final Map<String, HDFSClient> hdfsClientsMap = new HashMap<String, HDFSClient>();
	/**
	 * URI should always end with a '/'
	 */
	private Map<String, HDFSServer> uriToServerMap = new HashMap<String, HDFSServer>();

	private Map<String, HDFSServer> uriToServerCacheMap = new LinkedHashMap<String, HDFSServer>() {
		private static final long serialVersionUID = 1L;
		private int MAX_ENTRIES = 1 << 10;

		protected boolean removeEldestEntry(Map.Entry<String, HDFSServer> eldest) {
			return size() > MAX_ENTRIES;
		};
	};

	/**
	 * Singleton
	 */
	private HDFSManager() {
	}

	public EList<HDFSServer> getHdfsServers() {
		return HadoopManager.INSTANCE.getServers().getHdfsServers();
	}

	public void loadServers() {
		final IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
		for (HDFSServer server : getHdfsServers()) {
			uriToServerMap.put(server.getUri(), server);
			final IProject project = workspaceRoot.getProject(server.getName());
			if (!project.exists()) {
				server.setStatusCode(ServerStatus.NO_PROJECT_VALUE);
			}
			serverToProjectMap.put(server, server.getName());
			projectToServerMap.put(server.getName(), server);
		}
		IProject[] projects = workspaceRoot.getProjects();
		if (projects != null) {
			for (IProject p : projects) {
				if (p.getLocationURI() != null && HDFSFileSystem.SCHEME.equals(p.getLocationURI().getScheme())) {
					if (!projectToServerMap.keySet().contains(p)) {
						logger.error("HDFS project with no server associated being closed:" + p.getName());
						try {
							p.close(new NullProgressMonitor());
							logger.error("HDFS project with no server associated closed:" + p.getName());
						} catch (CoreException e) {
							logger.error("HDFS project with no server associated cannot be closed:" + p.getName(), e);
						}
					}
				}
			}
		}
	}

	/**
	 * Creates and adds an HDFS server definition. This also creates a local
	 * project which represents server file system via EFS.
	 * 
	 * @param hdfsURI
	 * @return
	 * @throws CoreException
	 */
	public HDFSServer createServer(String name, java.net.URI hdfsURI, String userId, List<String> groupIds,String version) throws CoreException {
		if (hdfsURI.getPath() == null || hdfsURI.getPath().length() < 1) {
			try {
				hdfsURI = new java.net.URI(hdfsURI.toString() + "/");
			} catch (URISyntaxException e) {
			}
		}
		if (ResourcesPlugin.getWorkspace().getRoot().getProject(name).exists())
			throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID, "Project with name '" + name + "' already exists"));
		HDFSServer hdfsServer = HadoopFactory.eINSTANCE.createHDFSServer();
		hdfsServer.setName(name);
		hdfsServer.setUri(hdfsURI.toString());
		hdfsServer.setLoaded(true);
		hdfsServer.setVersion(version);
		if (userId != null)
			hdfsServer.setUserId(userId);
		if (groupIds != null)
			for (String groupId : groupIds)
				hdfsServer.getGroupIds().add(groupId);
		getHdfsServers().add(hdfsServer);
		HadoopManager.INSTANCE.saveServers();
		uriToServerMap.put(hdfsServer.getUri(), hdfsServer);
		serverToProjectMap.put(hdfsServer, name);
		projectToServerMap.put(name, hdfsServer);
		createIProject(name, hdfsURI);
		return hdfsServer;
	}

	/**
	 * @param name
	 * @param hdfsURI
	 * @return
	 * @throws CoreException
	 */
	private IProject createIProject(String name, final java.net.URI hdfsURI) {
		final IWorkspace workspace = ResourcesPlugin.getWorkspace();
		final IProject project = workspace.getRoot().getProject(name);
		final IProjectDescription pd = workspace.newProjectDescription(name);
	    WorkspaceJob operation = new WorkspaceJob("Adding HDFS Location") {

			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
				monitor.beginTask("Creating Project", 100);
				try {
					pd.setLocationURI(hdfsURI);
					project.create(pd, new SubProgressMonitor(monitor, 70));
					project.open(IResource.BACKGROUND_REFRESH, new SubProgressMonitor(monitor, 30));
					RepositoryProvider.map(project, HDFSTeamRepositoryProvider.ID);
					return Status.OK_STATUS;
				} catch (final CoreException e) {
					logger.error("error found in creating HDFS site", e);
					Display.getDefault().syncExec(new Runnable(){
						public void run(){
							MessageDialog.openError(Display.getDefault().getActiveShell(), 
									"HDFS Error", "Unable to create HDFS site :"+e.getMessage());
						}
					});
					deleteServer(getServer(hdfsURI.toString()));
					return e.getStatus();
				} finally {
					monitor.done();
				}
			}
		   };
		operation.setPriority(Job.LONG);
		operation.setUser(true);
		operation.setRule(project);
		operation.schedule();
		return project;
	}

	public HDFSServer getServer(String uri) {
		if (uri != null && !uriToServerCacheMap.containsKey(uri)) {
			String tmpUri = uri;
			HDFSServer serverU = uriToServerMap.get(tmpUri);
			while (serverU == null) {
				int lastSlashIndex = tmpUri.lastIndexOf('/');
				tmpUri = lastSlashIndex < 0 ? null : tmpUri.substring(0, lastSlashIndex);
				if (tmpUri != null)
					serverU = uriToServerMap.get(tmpUri + "/");
				else
					break;
			}
			if (serverU != null)
				uriToServerCacheMap.put(uri, serverU);
		}
		return uriToServerCacheMap.get(uri);
	}
	
	

	public String getProjectName(HDFSServer server) {
		return serverToProjectMap.get(server);
	}

	/**
	 * @param string
	 */
	public void startServerOperation(String uri) {
		HDFSServer server = getServer(uri);
		if (server != null && !server.getOperationURIs().contains(uri)) {
			server.getOperationURIs().add(uri);
		}
	}

	/**
	 * @param string
	 */
	public void stopServerOperation(String uri) {
		HDFSServer server = getServer(uri);
		if (server != null) {
			server.getOperationURIs().remove(uri);
		}
	}

	public boolean isServerOperationRunning(String uri) {
		HDFSServer server = getServer(uri);
		if (server != null) {
			return server.getOperationURIs().contains(uri);
		}
		return false;
	}

	/**
	 * @param server
	 */
	public void deleteServer(HDFSServer server) {
		getHdfsServers().remove(server);
		String projectName = this.serverToProjectMap.remove(server);
		this.projectToServerMap.remove(projectName);
		this.uriToServerMap.remove(server.getUri());
		this.uriToServerCacheMap.remove(server.getUri());
		HadoopManager.INSTANCE.saveServers();
		String tmpUri = server.getUri();
		while (tmpUri != null && uriToServerCacheMap.containsKey(tmpUri)) {
			uriToServerCacheMap.remove(tmpUri);
			int lastSlashIndex = tmpUri.lastIndexOf('/');
			tmpUri = lastSlashIndex < 0 ? null : tmpUri.substring(0, lastSlashIndex);
		}
		if(hdfsClientsMap.containsKey(server.getUri().toString())){
			hdfsClientsMap.remove(server.getUri().toString());
		}
	}

	/**
	 * Provides the HDFSClient instance to
	 * 
	 * @param serverURI
	 * @return
	 * @throws CoreException
	 */
	public HDFSClient getClient(String serverURI,String hdfsVersion) throws CoreException {
		if (logger.isDebugEnabled())
			logger.debug("getClient(" + serverURI + "): Server=" + serverURI);
		HDFSServer server = getServer(serverURI);
		if (server != null && server.getStatusCode() == ServerStatus.DISCONNECTED_VALUE) {
			if (logger.isDebugEnabled())
				logger.debug("getClient(" + serverURI + "): Server timed out. Not returning client");
			throw new CoreException(new Status(IStatus.WARNING, Activator.BUNDLE_ID, "Server disconnected due to timeout. Please reconnect to server."));
		}
		if (hdfsClientsMap.containsKey(serverURI))
			return hdfsClientsMap.get(serverURI);
		else {
			try {
				java.net.URI sUri = serverURI == null ? new java.net.URI("hdfs://server") : new java.net.URI(serverURI);
				IConfigurationElement[] elementsFor = Platform.getExtensionRegistry().getConfigurationElementsFor("org.apache.hdt.core.hdfsClient");
				for (IConfigurationElement element : elementsFor) {
					if (sUri.getScheme().equals(element.getAttribute("protocol"))) {
						String version = element.getAttribute("protocolVersion");
						if(hdfsVersion.equalsIgnoreCase(version)){
							HDFSClient client = (HDFSClient) element.createExecutableExtension("class");
							hdfsClientsMap.put(serverURI, new InterruptableHDFSClient(serverURI, client));						
						}
					}
				}
			} catch (URISyntaxException e) {
				throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID, "Invalid server URI", e));
			}
			return hdfsClientsMap.get(serverURI);
		}
	}
	
	public static org.eclipse.core.runtime.IStatus addServer(String serverName, String location,
			String userId, List<String> groupId,String version) {
		try {
			HDFSManager.INSTANCE.createServer(serverName, new URI(location), userId, groupId,version);
		} catch (CoreException e) {
			logger.warn(e.getMessage(), e);
			return e.getStatus();
		} catch (URISyntaxException e) {
			logger.warn(e.getMessage(), e);
			return new Status(Status.ERROR,"unknown",e.getMessage(),e);
		}
		return Status.OK_STATUS;
	}
}
