/*
 * Copyright (c) 2010-2017 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.web.boot.actuator;

import com.evolveum.midpoint.web.security.*;

import com.evolveum.midpoint.web.security.provider.PasswordProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;

/**
 * @author skublik
 */
//@Order(SecurityProperties.BASIC_AUTH_ORDER - 2)
//@Configuration
//@Profile("!test")
public class ActuatorWebSecurityConfig extends WebSecurityConfigurerAdapter {

//    @Autowired
//    private AuthenticationProvider authenticationProvider;

    @Autowired
    private MidPointGuiAuthorizationEvaluator accessDecisionManager;

    @Override
    public void configure(WebSecurity web) throws Exception {
        web.ignoring().antMatchers("/actuator");
        web.ignoring().antMatchers("/actuator/health");
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {

        http
        .antMatcher("/actuator/**")
        .authorizeRequests()
        .accessDecisionManager(accessDecisionManager)
        .anyRequest().fullyAuthenticated()
        .and()
        .httpBasic()
        .and()
        .formLogin().disable()
        .csrf().disable()
        .exceptionHandling().authenticationEntryPoint(new HttpAuthenticationEntryPoint())
        .and()
        .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.NEVER);

    }

    @Override
    protected AuthenticationManager authenticationManager() throws Exception {
        return super.authenticationManager();
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.authenticationProvider(new PasswordProvider());
    }
}

