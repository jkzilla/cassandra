/*
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
package org.apache.cassandra.auth;

import java.util.*;

public class AllowAllAuthorizer implements IAuthorizer
{
    @Override
    public boolean requireAuthorization()
    {
        return false;
    }

    public Map<IResource, PermissionSets> allPermissionSets(RoleResource role)
    {
        throw new UnsupportedOperationException();
    }

    public Set<Permission> grant(AuthenticatedUser performer, Set<Permission> permissions, IResource resource, RoleResource to, GrantMode... grantModes)
    {
        throw new UnsupportedOperationException("GRANT operation is not supported by AllowAllAuthorizer");
    }

    public Set<Permission> revoke(AuthenticatedUser performer, Set<Permission> permissions, IResource resource, RoleResource from, GrantMode... grantModes)
    {
        throw new UnsupportedOperationException("REVOKE operation is not supported by AllowAllAuthorizer");
    }

    public void revokeAllFrom(RoleResource droppedRole)
    {
    }

    public Set<RoleResource> revokeAllOn(IResource droppedResource)
    {
        return Collections.emptySet();
    }

    public Set<PermissionDetails> list(Set<Permission> permissions, IResource resource, RoleResource of)
    {
        throw new UnsupportedOperationException("LIST PERMISSIONS operation is not supported by AllowAllAuthorizer");
    }

    public Set<IResource> protectedResources()
    {
        return Collections.emptySet();
    }

    public void validateConfiguration()
    {
    }

    public void setup()
    {
    }
}
