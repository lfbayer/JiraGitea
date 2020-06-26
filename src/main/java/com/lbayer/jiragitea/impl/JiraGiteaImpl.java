package com.lbayer.jiragitea.impl;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import com.lbayer.jiragitea.api.JiraGitea;

import javax.inject.Inject;
import javax.inject.Named;

@ExportAsService ({ JiraGitea.class})
@Named ("myPluginComponent")
public class JiraGiteaImpl implements JiraGitea
{
    @ComponentImport
    private final ApplicationProperties applicationProperties;

    @Inject
    public JiraGiteaImpl(final ApplicationProperties applicationProperties)
    {
        this.applicationProperties = applicationProperties;
    }

    public String getName()
    {
        if(null != applicationProperties)
        {
            return "JiraGitea:" + applicationProperties.getDisplayName();
        }
        
        return "JiraGitea";
    }
}