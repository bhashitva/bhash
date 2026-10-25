// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import dev.bhash.frontend.Identifiers;
import java.util.Map;

/** Predeclared collection type names live in the type namespace, not the keyword set. */
final class CollectionTypes {
    static final Map<String,Integer> ARITY = Map.of("सरणी",1,"सूची",1,"मानचित्र",2);
    static boolean builtin(String name) { return ARITY.containsKey(Identifiers.key(name)); }
    private CollectionTypes() {}
}
