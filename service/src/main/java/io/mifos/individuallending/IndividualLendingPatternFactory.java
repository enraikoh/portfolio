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
package io.mifos.individuallending;

import com.google.gson.Gson;
import io.mifos.individuallending.internal.mapper.CaseParametersMapper;
import io.mifos.individuallending.internal.repository.CaseParametersEntity;
import io.mifos.individuallending.internal.repository.CaseParametersRepository;
import io.mifos.products.spi.ProductCommandDispatcher;
import io.mifos.products.spi.PatternFactory;
import io.mifos.portfolio.service.ServiceConstants;
import io.mifos.individuallending.api.v1.domain.caseinstance.CaseParameters;
import io.mifos.individuallending.api.v1.domain.workflow.Action;
import io.mifos.portfolio.api.v1.domain.Case;
import io.mifos.portfolio.api.v1.domain.ChargeDefinition;
import io.mifos.portfolio.api.v1.domain.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static io.mifos.individuallending.api.v1.domain.product.AccountDesignators.*;
import static io.mifos.individuallending.api.v1.domain.product.ChargeIdentifiers.*;

/**
 * @author Myrle Krantz
 */
@SuppressWarnings("unused")
@Component
public class IndividualLendingPatternFactory implements PatternFactory {
  final static private String INDIVIDUAL_LENDING_PACKAGE = "io.mifos.individuallending.api.v1";
  private final CaseParametersRepository caseParametersRepository;
  private IndividualLendingCommandDispatcher individualLendingCommandDispatcher;
  private final Gson gson;

  @Autowired
  IndividualLendingPatternFactory(
          final CaseParametersRepository caseParametersRepository,
          final IndividualLendingCommandDispatcher individualLendingCommandDispatcher,
          @Qualifier(ServiceConstants.GSON_NAME) final Gson gson)
  {
    this.caseParametersRepository = caseParametersRepository;
    this.individualLendingCommandDispatcher = individualLendingCommandDispatcher;
    this.gson = gson;
  }

  @Override
  public Pattern pattern() {

    final Set<String> individualLendingRequiredAccounts = new HashSet<>();
    individualLendingRequiredAccounts.add(CUSTOMER_LOAN);
    individualLendingRequiredAccounts.add(CONSUMER_LOAN_LEDGER);
    individualLendingRequiredAccounts.add(LOAN_FUNDS_SOURCE);
    individualLendingRequiredAccounts.add(PROCESSING_FEE_INCOME);
    individualLendingRequiredAccounts.add(ORIGINATION_FEE_INCOME);
    individualLendingRequiredAccounts.add(DISBURSEMENT_FEE_INCOME);
    individualLendingRequiredAccounts.add(INTEREST_INCOME);
    individualLendingRequiredAccounts.add(INTEREST_ACCRUAL);
    individualLendingRequiredAccounts.add(LATE_FEE_INCOME);
    individualLendingRequiredAccounts.add(LATE_FEE_ACCRUAL);
    individualLendingRequiredAccounts.add(ARREARS_ALLOWANCE);
    individualLendingRequiredAccounts.add(ENTRY);
    return new Pattern(INDIVIDUAL_LENDING_PACKAGE, individualLendingRequiredAccounts);
  }

  @Override
  public List<ChargeDefinition> charges() {
    return defaultIndividualLoanCharges();
  }

  public static List<ChargeDefinition> defaultIndividualLoanCharges() {
    final List<ChargeDefinition> ret = new ArrayList<>();
    final ChargeDefinition processingFee = charge(
            PROCESSING_FEE_NAME,
            Action.OPEN,
            BigDecimal.valueOf(0.01),
            ENTRY,
            PROCESSING_FEE_INCOME);

    final ChargeDefinition loanOriginationFee = charge(
            LOAN_ORIGINATION_FEE_NAME,
            Action.APPROVE,
            BigDecimal.valueOf(0.01),
            ENTRY,
            ORIGINATION_FEE_INCOME);

    final ChargeDefinition loanFundsAllocation = charge(
            LOAN_FUNDS_ALLOCATION_ID,
            Action.APPROVE,
            BigDecimal.valueOf(1.00),
            LOAN_FUNDS_SOURCE,
            CONSUMER_LOAN_LEDGER);

    final ChargeDefinition disbursementFee = charge(
            DISBURSEMENT_FEE_NAME,
            Action.DISBURSE,
            BigDecimal.valueOf(0.001),
            ENTRY,
            DISBURSEMENT_FEE_INCOME);

    //TODO: Make proportional to payment rather than loan amount.
    final ChargeDefinition lateFee = charge(
            LATE_FEE_NAME,
            Action.ACCEPT_PAYMENT,
            BigDecimal.valueOf(0.01),
            CUSTOMER_LOAN,
            LATE_FEE_INCOME);
    lateFee.setAccrueAction(Action.MARK_LATE.name());
    lateFee.setAccrualAccountDesignator(LATE_FEE_ACCRUAL);

    //TODO: Make multiple write off allowance charges.
    final ChargeDefinition writeOffAllowanceCharge = charge(
            ALLOW_FOR_WRITE_OFF_NAME,
            Action.MARK_LATE,
            BigDecimal.valueOf(0.30),
            CONSUMER_LOAN_LEDGER,
            ARREARS_ALLOWANCE);

    final ChargeDefinition interestCharge = charge(
            INTEREST_NAME,
            Action.ACCEPT_PAYMENT,
            BigDecimal.valueOf(0.05),
            CUSTOMER_LOAN,
            CONSUMER_LOAN_LEDGER);
    interestCharge.setForCycleSizeUnit(ChronoUnit.YEARS);
    interestCharge.setAccrueAction(Action.APPLY_INTEREST.name());
    interestCharge.setAccrualAccountDesignator(INTEREST_ACCRUAL);

    final ChargeDefinition disbursementReturnCharge = charge(
            RETURN_DISBURSEMENT_NAME,
            Action.CLOSE,
            BigDecimal.valueOf(1.0),
            CONSUMER_LOAN_LEDGER,
            LOAN_FUNDS_SOURCE);

    ret.add(processingFee);
    ret.add(loanOriginationFee);
    ret.add(disbursementFee);
    ret.add(lateFee);
    ret.add(writeOffAllowanceCharge);
    ret.add(interestCharge);
    ret.add(disbursementReturnCharge);

    return ret;
  }

  @Transactional
  @Override
  public void persistParameters(final Long caseId, final String parameters) {
    final CaseParameters caseParameters = gson.fromJson(parameters, CaseParameters.class);
    final CaseParametersEntity caseParametersEntity = CaseParametersMapper.map(caseId, caseParameters);
    caseParametersRepository.save(caseParametersEntity);
  }

  @Transactional
  @Override
  public void changeParameters(Long caseId, String parameters) {
    final CaseParameters caseParameters = gson.fromJson(parameters, CaseParameters.class);
    final CaseParametersEntity oldCaseParameters = caseParametersRepository.findByCaseId(caseId)
            .orElseThrow(() -> new IllegalArgumentException("Case id does not represent an individual loan: " + caseId));

    oldCaseParameters.setCustomerIdentifier(caseParameters.getCustomerIdentifier());
    oldCaseParameters.setBalanceRangeMaximum(caseParameters.getMaximumBalance());
    oldCaseParameters.setTermRangeTemporalUnit(caseParameters.getTermRange().getTemporalUnit());
    oldCaseParameters.setTermRangeMinimum(0);
    oldCaseParameters.setTermRangeMaximum(caseParameters.getTermRange().getMaximum());
    oldCaseParameters.setPaymentCycleTemporalUnit(caseParameters.getPaymentCycle().getTemporalUnit());
    oldCaseParameters.setPaymentCyclePeriod(caseParameters.getPaymentCycle().getPeriod());
    oldCaseParameters.setPaymentCycleAlignmentDay(caseParameters.getPaymentCycle().getAlignmentDay());
    oldCaseParameters.setPaymentCycleAlignmentWeek(caseParameters.getPaymentCycle().getAlignmentWeek());
    oldCaseParameters.setPaymentCycleAlignmentMonth(caseParameters.getPaymentCycle().getAlignmentMonth());

    caseParametersRepository.save(oldCaseParameters);
  }

  @Override
  public Optional<String> getParameters(final Long caseId) {
    return caseParametersRepository
            .findByCaseId(caseId)
            .map(CaseParametersMapper::mapEntity)
            .map(gson::toJson);
  }

  @Override
  public Set<String> getNextActionsForState(final Case.State state) {
    return getAllowedNextActionsForState(state).stream().map(Enum::name).collect(Collectors.toSet());
  }

  public static Set<Action> getAllowedNextActionsForState(Case.State state) {
    switch (state)
    {
      case CREATED:
        //noinspection ArraysAsListWithZeroOrOneArgument
        return new HashSet<>(Arrays.asList(Action.OPEN));
      case PENDING:
        return new HashSet<>(Arrays.asList(Action.DENY, Action.APPROVE));
      case APPROVED:
        return new HashSet<>(Arrays.asList(Action.DISBURSE, Action.CLOSE));
      case ACTIVE:
        return new HashSet<>(Arrays.asList(Action.CLOSE, Action.ACCEPT_PAYMENT, Action.MARK_LATE, Action.APPLY_INTEREST, Action.DISBURSE, Action.WRITE_OFF));
      case CLOSED:
        return Collections.emptySet();
      default:
        return Collections.emptySet();
    }
  }

  public ProductCommandDispatcher getIndividualLendingCommandDispatcher() {
    return this.individualLendingCommandDispatcher;
  }

  private static ChargeDefinition charge(
          final String name,
          final Action action,
          final BigDecimal defaultAmount,
          final String fromAccount,
          final String toAccount)
  {
    final ChargeDefinition ret = new ChargeDefinition();

    ret.setIdentifier(name.toLowerCase(Locale.US).replace(" ", "-"));
    ret.setName(name);
    ret.setDescription(name);
    ret.setChargeAction(action.name());
    ret.setAmount(defaultAmount);
    ret.setChargeMethod(ChargeDefinition.ChargeMethod.PROPORTIONAL);
    ret.setFromAccountDesignator(fromAccount);
    ret.setToAccountDesignator(toAccount);

    return ret;
  }
}
