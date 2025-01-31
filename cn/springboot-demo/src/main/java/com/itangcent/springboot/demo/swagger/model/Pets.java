/*
 *
 *  Copyright 2015 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package com.itangcent.springboot.demo.swagger.model;

import java.util.Objects;
import java.util.function.Predicate;

public class Pets {
  private Pets() {
    throw new UnsupportedOperationException();
  }

  public static Predicate<com.itangcent.springboot.demo.swagger.model.Pet> statusIs(final String status) {
    return new Predicate<com.itangcent.springboot.demo.swagger.model.Pet>() {
      @Override
      public boolean test(com.itangcent.springboot.demo.swagger.model.Pet input) {
        return Objects.equals(input.getStatus(), status);
      }
    };
  }

  public static Predicate<com.itangcent.springboot.demo.swagger.model.Pet> tagsContain(final String tag) {
    return new Predicate<com.itangcent.springboot.demo.swagger.model.Pet>() {
      @Override
      public boolean test(Pet input) {
        return input.getTags().stream().anyMatch(withName(tag));
      }
    };
  }

  private static Predicate<com.itangcent.springboot.demo.swagger.model.Tag> withName(final String tag) {
    return new Predicate<com.itangcent.springboot.demo.swagger.model.Tag>() {
      @Override
      public boolean test(Tag input) {
        return Objects.equals(input.getName(), tag);
      }
    };
  }
}
