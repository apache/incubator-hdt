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

import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.filesystem.provider.FileSystem;

/**
 * 
 * @author Srimanth Gunturi
 */
public class HDFSFileSystem extends FileSystem {
	
	public static final String SCHEME = "hdfs";

	@Override
	public IFileStore getStore(URI uri) {
		if(SCHEME.equals(uri.getScheme()))
			return new HDFSFileStore(new HDFSURI(uri));
		return null;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.core.filesystem.provider.FileSystem#canDelete()
	 */
	@Override
	public boolean canDelete() {
		return true;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.core.filesystem.provider.FileSystem#canWrite()
	 */
	@Override
	public boolean canWrite() {
		return true;
	}

}
