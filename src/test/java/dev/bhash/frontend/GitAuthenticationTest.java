// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GitAuthenticationTest {
    @Test void credentialsAreHostScopedEnvironmentOnlyAndDisableRedirects()throws Exception{
        var settings=Map.of("BHASH_GIT_HOST","github.com","BHASH_GIT_TOKEN","fixture-token");
        var env=new HashMap<String,String>();env.putAll(settings);
        var arguments=List.of("fetch","--","https://github.com/owner/private.git","0".repeat(40));
        var credentials=GitPackage.authentication(settings,arguments,env);
        assertEquals("2",env.get("GIT_CONFIG_COUNT"));assertEquals("http.https://github.com/.extraHeader",env.get("GIT_CONFIG_KEY_0"));
        assertEquals("Authorization: Basic "+credentials.encoded(),env.get("GIT_CONFIG_VALUE_0"));
        assertEquals("false",env.get("GIT_CONFIG_VALUE_1"));assertFalse(env.containsKey("BHASH_GIT_TOKEN"));
        assertTrue(arguments.stream().noneMatch(s->s.contains("fixture-token")||s.contains(credentials.encoded())));
        for(String remote:List.of("https://github.com.attacker.invalid/owner/lib.git","https://example.org/lib.git","file:///local/repo")){
            env.clear();GitPackage.authentication(settings,List.of("fetch",remote),env);assertFalse(env.containsKey("GIT_CONFIG_VALUE_0"));
        }
        env.clear();GitPackage.authentication(Map.of("BHASH_GIT_TOKEN","fixture-token"),arguments,env);assertTrue(env.isEmpty());
        assertThrows(IOException.class,()->GitPackage.authentication(Map.of("BHASH_GIT_HOST","github.com","BHASH_GIT_TOKEN","bad\ntoken"),arguments,new HashMap<>()));
    }
}
