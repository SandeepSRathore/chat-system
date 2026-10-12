package com.chatsystem.api.group;

import com.chatsystem.api.config.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

    public record CreateGroupRequest(@NotBlank @Size(max = 64) String name, @NotNull List<Long> memberIds) {
    }

    public record AddMembersRequest(@NotNull @Size(min = 1) List<Long> userIds) {
    }

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping
    List<GroupView> list(@AuthenticationPrincipal Jwt jwt) {
        return groupService.groupsOf(CurrentUser.id(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GroupView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateGroupRequest req) {
        return groupService.create(CurrentUser.id(jwt), req.name(), req.memberIds());
    }

    @GetMapping("/{groupId}")
    GroupView get(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId) {
        return groupService.get(CurrentUser.id(jwt), groupId);
    }

    @PostMapping("/{groupId}/members")
    GroupView addMembers(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId,
                         @Valid @RequestBody AddMembersRequest req) {
        return groupService.addMembers(CurrentUser.id(jwt), groupId, req.userIds());
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId, @PathVariable long userId) {
        groupService.removeMember(CurrentUser.id(jwt), groupId, userId);
    }
}
