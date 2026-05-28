package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spec 3.6: parses {@code @username} mentions in comment bodies. Matching is
 * case-insensitive (the spec's only stated constraint on usernames here).
 * Unknown usernames are silently skipped — they are not errors.
 */
@Component
@RequiredArgsConstructor
public class MentionParser {

    private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9_.-]+)");

    private final UserRepository userRepository;

    public Set<Long> extractMentionedUserIds(String content) {
        if (content == null || content.isBlank()) {
            return Set.of();
        }
        Set<String> usernames = new LinkedHashSet<>();
        Matcher m = MENTION.matcher(content);
        while (m.find()) {
            usernames.add(m.group(1));
        }
        if (usernames.isEmpty()) {
            return Set.of();
        }
        Set<Long> userIds = new LinkedHashSet<>();
        for (String username : usernames) {
            userRepository.findByUsernameIgnoreCase(username)
                    .map(User::getId)
                    .ifPresent(userIds::add);
        }
        return userIds;
    }
}
