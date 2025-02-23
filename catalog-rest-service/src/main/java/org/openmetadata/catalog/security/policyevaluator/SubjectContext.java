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

package org.openmetadata.catalog.security.policyevaluator;

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.catalog.Entity;
import org.openmetadata.catalog.entity.teams.Team;
import org.openmetadata.catalog.entity.teams.User;
import org.openmetadata.catalog.type.EntityReference;

/** Subject context used for Access Control Policies */
@Slf4j
public class SubjectContext {
  @Getter protected final User user;

  protected SubjectContext(User user) {
    this.user = user;
  }

  public boolean isAdmin() {
    return Boolean.TRUE.equals(user.getIsAdmin());
  }

  public boolean isBot() {
    return Boolean.TRUE.equals(user.getIsBot());
  }

  public boolean isOwner(EntityReference owner) {
    if (owner == null) {
      return false;
    }
    if (owner.getType().equals(Entity.USER) && owner.getName().equals(user.getName())) {
      return true; // Owner is same as user.
    }
    if (owner.getType().equals(Entity.TEAM)) {
      for (EntityReference userTeam : user.getTeams()) {
        if (userTeam.getName().equals(owner.getName())) {
          return true; // Owner is a team, and the user is part of this team.
        }
      }
    }
    return false;
  }

  // Iterate over all the policies
  public Iterator<PolicyContext> getPolicies() {
    return new UserPolicyIterator(user, new ArrayList<>());
  }

  @Getter
  static class PolicyContext {
    private final String entityName;
    private final String roleName;
    private final String policyName;
    private final List<CompiledRule> rules;

    PolicyContext(String entity, String role, String policy, List<CompiledRule> rules) {
      this.entityName = entity;
      this.roleName = role;
      this.policyName = policy;
      this.rules = rules;
    }
  }

  /** PolicyIterator goes over policies in a set of policies one by one. */
  static class PolicyIterator implements Iterator<PolicyContext> {
    private final String entityName;
    private final String roleName;
    private int policyIndex = 0;
    private final List<EntityReference> policies;

    PolicyIterator(String entityName, String roleName, List<EntityReference> policy) {
      this.entityName = entityName;
      this.roleName = roleName;
      this.policies = listOrEmpty(policy);
    }

    @Override
    public boolean hasNext() {
      return policyIndex < policies.size();
    }

    @Override
    public PolicyContext next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      EntityReference policy = policies.get(policyIndex++);
      return new PolicyContext(
          entityName, roleName, policy.getName(), PolicyCache.getInstance().getPolicyRules(policy.getId()));
    }
  }

  /** RolePolicyIterator goes over policies in a set of roles one by one. */
  static class RolePolicyIterator implements Iterator<PolicyContext> {
    private final String entityName;
    private int roleIndex = 0;
    private final List<EntityReference> roles;
    private PolicyIterator policyIterator;

    RolePolicyIterator(String entityName, List<EntityReference> roles) {
      this.entityName = entityName;
      this.roles = listOrEmpty(roles);
      this.policyIterator = new PolicyIterator(entityName, null, null);
    }

    @Override
    public boolean hasNext() {
      if (policyIterator.hasNext()) {
        return true;
      }
      if (roleIndex < roles.size()) {
        policyIterator =
            new PolicyIterator(
                entityName,
                roles.get(roleIndex).getName(),
                RoleCache.getInstance().getRole(roles.get(roleIndex).getId()).getPolicies());
        roleIndex++;
      }
      return policyIterator.hasNext();
    }

    @Override
    public PolicyContext next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return policyIterator.next();
    }
  }

  /**
   * A class that allows iterating over policies of a user using iterator of iterators. For a user, the policies in user
   * roles are visited one by one, followed by policies in the teams that a user belongs to.
   */
  static class UserPolicyIterator implements Iterator<PolicyContext> {
    private final User user;
    private int iteratorIndex = 0;
    private final List<Iterator<PolicyContext>> iterators = new ArrayList<>();
    private final List<UUID> teamsVisited;

    /** Policy iterator for a user */
    UserPolicyIterator(User user, List<UUID> teamsVisited) {
      this.user = user;
      this.teamsVisited = teamsVisited;
      iterators.add(new RolePolicyIterator(user.getName(), user.getRoles()));
    }

    @Override
    public boolean hasNext() {
      if (iterators.get(iteratorIndex).hasNext()) {
        return true;
      }
      while (iteratorIndex < iterators.size()) {
        iteratorIndex++;
        if (iteratorIndex == 1) { // Lazy load policies from the teams that the user belongs to
          for (EntityReference team : listOrEmpty(user.getTeams())) {
            iterators.add(new TeamPolicyIterator(team.getId(), teamsVisited));
          }
        }
        if (iteratorIndex < iterators.size() && iterators.get(iteratorIndex).hasNext()) {
          return true;
        }
      }
      return false;
    }

    @Override
    public PolicyContext next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return iterators.get(iteratorIndex).next();
    }
  }

  /**
   * A class that allows iterating over policies of a team using iterator of iterators. For a team, the policies in team
   * roles are visited one by one, followed by the policies in the parent teams.
   */
  static class TeamPolicyIterator implements Iterator<PolicyContext> {
    private final UUID teamId;
    private int iteratorIndex = 0;
    private final Team team;
    private final List<Iterator<PolicyContext>> iterators = new ArrayList<>();
    private final List<UUID> teamsVisited;

    /** Policy iterator for a team */
    TeamPolicyIterator(UUID teamId, List<UUID> teamsVisited) {
      this.teamId = teamId;
      this.teamsVisited = teamsVisited;
      this.team = SubjectCache.getInstance().getTeam(teamId);
      iterators.add(new RolePolicyIterator(team.getName(), team.getDefaultRoles()));
      iterators.add(new PolicyIterator(team.getName(), null, team.getPolicies()));
    }

    @Override
    public boolean hasNext() {
      // If a team is already visited (because user can belong to multiple teams
      // and a team can belong to multiple teams) then don't visit it again
      if (teamsVisited.contains(teamId)) {
        return false;
      }
      if (iterators.get(iteratorIndex).hasNext()) {
        return true;
      }
      while (iteratorIndex < iterators.size()) {
        iteratorIndex++;
        if (iteratorIndex == 2) { // Lazy load parent teams and get policies from them
          for (EntityReference t : listOrEmpty(team.getParents())) {
            iterators.add(new TeamPolicyIterator(t.getId(), teamsVisited));
          }
        }
        if (iteratorIndex < iterators.size() && iterators.get(iteratorIndex).hasNext()) {
          return true;
        }
      }
      teamsVisited.add(teamId);
      return false;
    }

    @Override
    public PolicyContext next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return iterators.get(iteratorIndex).next();
    }
  }
}
