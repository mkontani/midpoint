/*
 * Copyright (c) 2010-2019 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.midpoint.repo.sql.helpers.mapper;

import com.evolveum.midpoint.prism.Referencable;
import com.evolveum.midpoint.xml.ns._public.common.common_4.ObjectReferenceType;

/**
 * Created by Viliam Repan (lazyman).
 */
public abstract class ReferenceMapper<T> implements Mapper<Referencable, T> {

    protected ObjectReferenceType buildReference(Referencable input) {
        ObjectReferenceType objectRef;
        if (input instanceof ObjectReferenceType) {
            objectRef = (ObjectReferenceType) input;
        } else {
            objectRef = new ObjectReferenceType();
            objectRef.setupReferenceValue(input.asReferenceValue());
        }

        return objectRef;
    }
}
