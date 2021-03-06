/*
 * Copyright 2017 The Mifos Initiative.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mifos.portfolio.service.internal.service;

import io.mifos.portfolio.api.v1.domain.AccountAssignment;
import io.mifos.portfolio.api.v1.domain.ChargeDefinition;
import io.mifos.portfolio.api.v1.domain.Product;
import io.mifos.portfolio.service.internal.mapper.ProductMapper;
import io.mifos.portfolio.service.internal.repository.ProductEntity;
import io.mifos.portfolio.service.internal.repository.ProductRepository;
import io.mifos.portfolio.service.internal.util.AccountingAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Myrle Krantz
 */
@Service
public class ProductService {

  private final ProductRepository productRepository;
  private final ChargeDefinitionService chargeDefinitionService;
  private final AccountingAdapter accountingAdapter;

  @Autowired
  public ProductService(final ProductRepository productRepository,
                        final ChargeDefinitionService chargeDefinitionService,
                        final AccountingAdapter accountingAdapter) {
    super();
    this.productRepository = productRepository;
    this.chargeDefinitionService = chargeDefinitionService;
    this.accountingAdapter = accountingAdapter;
  }

  public List<Product> findAllEntities() {
    return ProductMapper.map(this.productRepository.findAll());
  }

  public Optional<Product> findByIdentifier(final String identifier)
  {
    return productRepository.findByIdentifier(identifier).map(ProductMapper::map);
  }

  public Optional<Boolean> findEnabledByIdentifier(final String identifier) {
    return productRepository.findByIdentifier(identifier).map(ProductEntity::getEnabled);
  }

  public Boolean areChargeDefinitionsCoveredByAccountAssignments(final String identifier) {
    final Optional<Product> maybeProduct = findByIdentifier(identifier);
    if (!maybeProduct.isPresent())
      return false;
    final Product product = maybeProduct.get();
    final Set<AccountAssignment> accountAssignments = product.getAccountAssignments();
    final List<ChargeDefinition> chargeDefinitions = chargeDefinitionService.findAllEntities(identifier);
    return AccountingAdapter.accountAssignmentsCoverChargeDefinitions(accountAssignments, chargeDefinitions);
  }

  public Set<AccountAssignment> getIncompleteAccountAssignments(final String identifier) {
    final Set<String> requiredAccountDesignators = AccountingAdapter.getRequiredAccountDesignators(chargeDefinitionService.findAllEntities(identifier));

    final AccountAssignmentValidator accountAssignmentValidator
            = new AccountAssignmentValidator(findByIdentifier(identifier)
            .map(Product::getAccountAssignments)
            .orElse(Collections.emptySet()));

    return requiredAccountDesignators.stream()
            .map(accountAssignmentValidator::getDesignatorMapping)
            .filter(ProductService::accountAssignmentIsComplete)
            .collect(Collectors.toSet());
  }

  private static boolean accountAssignmentIsComplete(final AccountAssignment x) {
    return (x.getAccountIdentifier() == null) && (x.getLedgerIdentifier() == null);
  }

  private class AccountAssignmentValidator {
    private final Map<String, List<AccountAssignment>> accountAssignmentsMap;

    private AccountAssignmentValidator(final Set<AccountAssignment> accountAssignments)
    {
      accountAssignmentsMap = accountAssignments.stream().collect(Collectors.groupingBy(AccountAssignment::getDesignator));
    }

    private boolean designatorHasValidMapping(final String designator) {
      final List<AccountAssignment> accountAssignment = accountAssignmentsMap.get(designator);

      return ((accountAssignment != null) &&
              (accountAssignment.size() == 1) &&
              accountingAdapter.accountAssignmentRepresentsRealAccount(accountAssignment.get(0)));
    }

    private AccountAssignment getDesignatorMapping(final String designator) {
      if (designatorHasValidMapping(designator))
        return accountAssignmentsMap.get(designator).get(0);
      else
        return new AccountAssignment(designator, null);
    }
  }
}