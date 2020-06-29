package com.lbayer.jiragitea;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.atlassian.jira.bc.ServiceResult;
import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.bc.issue.IssueService.IssueResult;
import com.atlassian.jira.bc.issue.IssueService.TransitionValidationResult;
import com.atlassian.jira.bc.issue.IssueService.UpdateValidationResult;
import com.atlassian.jira.bc.user.search.UserSearchService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueInputParameters;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.IssueWorkflowManager;
import com.google.gson.Gson;
import com.google.gson.internal.StringMap;
import com.opensymphony.workflow.loader.ActionDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JiraGiteaWebhookServlet extends HttpServlet{
    private static final Logger log = LoggerFactory.getLogger(JiraGiteaWebhookServlet.class);

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        String contentType = req.getContentType();
        if (contentType == null || !contentType.startsWith("application/json")) {
            resp.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "Type must be 'application/json'");
            return;
        }

        Gson gson = new Gson();
        Object json = gson.fromJson(req.getReader(), Object.class);
        if (json instanceof StringMap) {
            StringMap stringMap = (StringMap) json;
            for (Object commit : (List) stringMap.get("commits")) {
                handleCommit((StringMap) commit);
            }
        }

        resp.setContentType("text/plain");
        resp.getWriter().write("OK");
    }

    public static void main(String[] args)
    {
        JiraGiteaWebhookServlet s = new JiraGiteaWebhookServlet();
        List<Action> list = s.parseActions("JG-1 #comment made an edit 2\n").collect(Collectors.toList());
        for (Action action : list) {
            System.out.println(action.message);
        }
    }

    private ApplicationUser findUser(String email)
    {
        UserSearchService userSearch = ComponentAccessor.getComponent(UserSearchService.class);
        Iterable<ApplicationUser> users = userSearch.findUsersByEmail(email);
        for (ApplicationUser user : users) {
            return user;
        }

        return null;
    }

    private Optional<Integer> findActionId(Issue issue, ApplicationUser user, String transitionName)
    {
        String lowerCaseTransition = transitionName.toLowerCase();

        IssueWorkflowManager issueWorkflowManager = ComponentAccessor.getComponent(IssueWorkflowManager.class);
        List<ActionDescriptor> actions = issueWorkflowManager.getSortedAvailableActions(issue, user);
        return actions.stream()
                .filter(a -> {
                    String lowerCaseAction = a.getName().toLowerCase();
                    return lowerCaseAction.equals(lowerCaseTransition) || lowerCaseAction.startsWith(transitionName + " ");
                })
                .findFirst()
                .map(ActionDescriptor::getId);
    }

    private void logErrors(ServiceResult result) {
        result.getErrorCollection()
                .getErrorMessages()
                .forEach(log::warn);
    }

    private void handleCommit(StringMap stringMap) {
        Commit commit = new Commit(stringMap);
        log.info("Processing commit: {} ({})\n", commit.id, commit.committer.email);

        ApplicationUser user = findUser(commit.committer.email);
        if (user == null) {
            log.warn("No user found for email address: {}", commit.committer.email);
            return;
        }

//        String propKey = "jiragitea:commit:" + commit.id;
//        ApplicationProperties applicationProperties = ComponentAccessor.getApplicationProperties();
//        if (applicationProperties.getOption(propKey)) {
//            // duplicate event
//            return;
//        }
//
//        applicationProperties.setOption(propKey, true);

        JiraAuthenticationContext authContext = ComponentAccessor.getJiraAuthenticationContext();
        authContext.setLoggedInUser(user);
        try {
            parseActions(commit.message).forEach(action -> handleAction(user, commit, action));
        }
        finally {
            authContext.clearLoggedInUser();
        }
    }

    private void handleAction(ApplicationUser user, Commit commit, Action action) {
        IssueService issueService = ComponentAccessor.getIssueService();
        IssueResult issueResult = issueService.getIssue(user, action.key);
        if (!issueResult.isValid()) {
            log.warn("Cannot resolve issue '{}' (user: {})", action.key, user.getUsername());
            logErrors(issueResult);
            return;
        }

        MutableIssue issue = issueResult.getIssue();
        Long issueId = issue.getId();

        if (!action.transitionName.equalsIgnoreCase("comment")) {
            Optional<Integer> actionId = findActionId(issue, user, action.transitionName);
            if (!actionId.isPresent()) {
                log.warn("No action found for transition: {}", action.transitionName);
                return;
            }

            TransitionValidationResult validationResult =
                    issueService.validateTransition(user, issueId, actionId.get(), issueService.newIssueInputParameters());
            if (!validationResult.isValid()) {
                log.warn("Validation error transitioning issue {} (user: {})", action.key, user.getUsername());
                logErrors(validationResult);
                return;
            }

            IssueResult result = issueService.transition(user, validationResult);
            if (!result.isValid()) {
                log.warn("Unable to transition issue {} (user: {})", action.key, user.getUsername());
                logErrors(validationResult);
                return;
            }
        }

        IssueInputParameters issueInputParameters = issueService.newIssueInputParameters()
                .setComment(buildComment(commit, action));

        UpdateValidationResult uvr = issueService.validateUpdate(user, issueId, issueInputParameters);
        if (!uvr.isValid()) {
            log.warn("Validation error adding comment to issue {} (user: {})", action.key, user.getUsername());
            logErrors(uvr);
            return;
        }

        IssueResult updateResult = issueService.update(user, uvr);
        if (!updateResult.isValid()) {
            log.warn("Unable to update issue {} (user: {})", action.key, user.getUsername());
            logErrors(updateResult);
        }
    }

    private String buildComment(Commit commit, Action action)
    {
        return "[git commit " + commit.id.substring(0, 10) + "|" + commit.url + "]\n" + action.message;
    }

    private Stream<Action> parseActions(String message) {
        Pattern pattern = Pattern.compile("^(\\w+-\\d+)\\s*#(\\S+)\\s*(.*)\\s*$");

        return Arrays.stream(message.split("\n"))
                .map(pattern::matcher)
                .filter(Matcher::matches)
                .map(matcher -> {
                    Action action = new Action();
                    action.key = matcher.group(1);
                    action.transitionName = matcher.group(2);
                    action.message = matcher.group(3);
                    return action;
                });
    }

    private static class Action
    {
        String key;
        String transitionName;
        String message;
    }

    private static class Commit
    {
        User committer;
        User author;
        String message;
        String url;
        String id;

        Commit(StringMap stringMap) {
            id = (String) stringMap.get("id");
            message = (String) stringMap.get("message");
            author = new User((StringMap) stringMap.get("author"));
            committer = new User((StringMap) stringMap.get("committer"));
            url = (String) stringMap.get("url");
        }
    }

    private static class User
    {
        String name;
        String email;

        User(StringMap stringMap) {
            name = (String) stringMap.get("name");
            email = (String) stringMap.get("email");
        }
    }
}