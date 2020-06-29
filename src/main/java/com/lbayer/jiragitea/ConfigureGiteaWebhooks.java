package com.lbayer.jiragitea;

import com.atlassian.jira.web.action.JiraWebActionSupport;
import com.atlassian.plugin.spring.scanner.annotation.component.Scanned;

@Scanned
public class ConfigureGiteaWebhooks extends JiraWebActionSupport
{
    @Override
    public String doDefault() throws Exception
    {
        return super.doDefault();
    }
}
