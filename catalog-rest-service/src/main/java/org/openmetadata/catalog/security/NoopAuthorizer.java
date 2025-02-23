/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.catalog.security;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import javax.ws.rs.core.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jdbi.v3.core.Jdbi;
import org.openmetadata.catalog.Entity;
import org.openmetadata.catalog.entity.teams.User;
import org.openmetadata.catalog.exception.EntityNotFoundException;
import org.openmetadata.catalog.jdbi3.EntityRepository;
import org.openmetadata.catalog.security.policyevaluator.OperationContext;
import org.openmetadata.catalog.security.policyevaluator.PolicyEvaluator;
import org.openmetadata.catalog.security.policyevaluator.ResourceContextInterface;
import org.openmetadata.catalog.type.EntityReference;
import org.openmetadata.catalog.type.Permission.Access;
import org.openmetadata.catalog.type.ResourcePermission;
import org.openmetadata.catalog.util.EntityUtil.Fields;
import org.openmetadata.catalog.util.RestUtil;

@Slf4j
public class NoopAuthorizer implements Authorizer {
  @Override
  public void init(AuthorizerConfiguration config, Jdbi jdbi) {
    addAnonymousUser();
  }

  @Override
  public List<ResourcePermission> listPermissions(SecurityContext securityContext) {
    // Return all operations.
    return PolicyEvaluator.getResourcePermissions(Access.ALLOW);
  }

  @Override
  public boolean isOwner(SecurityContext securityContext, EntityReference entityReference) {
    return true;
  }

  @Override
  public void authorize(
      SecurityContext securityContext,
      OperationContext operationContext,
      ResourceContextInterface resourceContext,
      boolean allowBots) {
    /* Always authorize */
  }

  private void addAnonymousUser() {
    String username = "anonymous";
    try {
      Entity.getEntityRepository(Entity.USER).getByName(null, username, Fields.EMPTY_FIELDS);
    } catch (EntityNotFoundException ex) {
      User user =
          new User()
              .withId(UUID.randomUUID())
              .withName(username)
              .withEmail(username + "@domain.com")
              .withUpdatedBy(username)
              .withUpdatedAt(System.currentTimeMillis());
      addOrUpdateUser(user);
    } catch (IOException e) {
      LOG.error("Failed to create anonymous user {}", username, e);
    }
  }

  private void addOrUpdateUser(User user) {
    try {
      EntityRepository<User> userRepository = Entity.getEntityRepository(Entity.USER);
      RestUtil.PutResponse<User> addedUser = userRepository.createOrUpdate(null, user);
      LOG.debug("Added anonymous user entry: {}", addedUser);
    } catch (IOException exception) {
      // In HA set up the other server may have already added the user.
      LOG.debug("Caught exception: {}", ExceptionUtils.getStackTrace(exception));
      LOG.debug("Anonymous user entry: {} already exists.", user);
    }
  }

  @Override
  public void authorizeAdmin(SecurityContext securityContext, boolean allowBots) {
    /* Always authorize */
  }
}
