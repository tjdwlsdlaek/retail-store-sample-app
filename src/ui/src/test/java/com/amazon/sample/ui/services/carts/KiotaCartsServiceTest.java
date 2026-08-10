/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.amazon.sample.ui.services.carts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazon.sample.ui.client.cart.CartClient;
import com.amazon.sample.ui.client.cart.carts.item.items.ItemsRequestBuilder;
import com.amazon.sample.ui.client.cart.carts.item.items.item.WithItemItemRequestBuilder;
import com.amazon.sample.ui.client.cart.models.Item;
import com.amazon.sample.ui.services.catalog.CatalogService;
import com.amazon.sample.ui.services.catalog.model.Product;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class KiotaCartsServiceTest {

  private static final String SESSION = "session-1";
  private static final String PRODUCT = "prod-1";

  private CartClient cartClient;
  private CatalogService catalogService;
  private KiotaCartsService service;

  @BeforeEach
  void setUp() {
    // Deep stubs let us assert against the final builder in the fluent chain.
    this.cartClient = mock(CartClient.class, RETURNS_DEEP_STUBS);
    this.catalogService = mock(CatalogService.class);
    this.service = new KiotaCartsService(cartClient, catalogService);
  }

  // Resolve each terminal builder ONCE and verify against the captured
  // reference. RETURNS_DEEP_STUBS returns the same child mock for a given
  // method+arguments, so capturing the mock up front is deterministic and
  // makes it explicit which instance is being verified -- as opposed to
  // re-walking the fluent chain inside every verify(...) call.
  private ItemsRequestBuilder itemsBuilder() {
    return cartClient.carts().byCustomerId(SESSION).items();
  }

  private WithItemItemRequestBuilder itemBuilder() {
    return itemsBuilder().byItemId(PRODUCT);
  }

  @Test
  void removeItemDoesNotCallBackendUntilSubscribed() {
    WithItemItemRequestBuilder itemBuilder = itemBuilder();

    Mono<Void> mono = service.removeItem(SESSION, PRODUCT);

    // Regression guard: the previous implementation fired the blocking
    // delete() eagerly at assembly time. The hardened version must defer it
    // until subscription so it is composed into the returned reactive chain.
    verify(itemBuilder, never()).delete();

    StepVerifier.create(mono).verifyComplete();

    verify(itemBuilder).delete();
  }

  @Test
  void removeItemPropagatesBackendError() {
    WithItemItemRequestBuilder itemBuilder = itemBuilder();
    RuntimeException backendFailure = new RuntimeException("delete failed");
    doThrow(backendFailure).when(itemBuilder).delete();

    // Composing the blocking call into the returned Mono means a backend
    // failure now surfaces to the subscriber instead of being swallowed
    // (the pre-hardening code discarded the result and returned Mono.empty()).
    StepVerifier.create(service.removeItem(SESSION, PRODUCT))
      .verifyErrorMatches(t -> t == backendFailure);
  }

  @Test
  void addItemPostsMappedItemOnSubscribe() {
    Product product = new Product(PRODUCT, "Test", "desc", 100, List.of());
    when(catalogService.getProduct(PRODUCT)).thenReturn(Mono.just(product));

    ItemsRequestBuilder itemsBuilder = itemsBuilder();

    Mono<Void> mono = service.addItem(SESSION, PRODUCT, 2);

    // Deferred: the blocking POST must not run before subscription.
    verify(itemsBuilder, never()).post(any(Item.class));

    StepVerifier.create(mono).verifyComplete();

    ArgumentCaptor<Item> captor = ArgumentCaptor.forClass(Item.class);
    verify(itemsBuilder).post(captor.capture());

    Item posted = captor.getValue();
    assertThat(posted.getItemId()).isEqualTo(PRODUCT);
    assertThat(posted.getQuantity()).isEqualTo(2);
    assertThat(posted.getUnitPrice()).isEqualTo(100);
  }
}
