/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.solr;

import com.kpmg.aml.screening.entity.dto.CustomerInfo;
import com.kpmg.aml.screening.entity.dto.SanctionTypeView;
import com.kpmg.aml.screening.util.NameRefinerUtil;
import com.kpmg.aml.screening.util.SriLankanNicUtil;
import com.kpmg.aml.screening.util.SriLankanNicUtil.NicPair;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 *
 * @author user
 */
@Component
public class SanctionStrategyBuilder {

    private final ScreeningSolrQueryBuilder queryBuilder;
    private final SolrQueryExecutor executor;

    public SanctionStrategyBuilder(SolrQueryExecutor executor) {
        this.queryBuilder = new ScreeningSolrQueryBuilder();
        this.executor = executor;
    }

    /*
       1. Invoked ONCE before the loop starts
       2. Builds a composed stratergy per sanctionId (scenario)
       3. Switch runs once per unique sanction type - never per record 
     */
    public Map<Long, SanctionScreeningStrategy> build(List<SanctionTypeView> sanctionTypes) {

        // group by SanctionId --> list of sanction type strings 
        Map<Long, List<String>> grouped
                = sanctionTypes.stream()
                        .collect(Collectors.groupingBy(
                                SanctionTypeView::getSanctionId,
                                Collectors.mapping(SanctionTypeView::getSanctionType, Collectors.toList())
                        ));

        return grouped.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> composeStrategy(e.getValue())
                ));

    }

    private SanctionScreeningStrategy composeStrategy(List<String> types) {

        List<SanctionScreeningStrategy> stratergies = types.stream()
                .map(this::resolveStrategy)
                .toList();

        return customer -> {
            CustomerInfo cleansed = new CustomerInfo(
                    customer.clientId(),
                    NameRefinerUtil.refineName(customer.name()),
                    customer.nic(),
                    customer.passport(),
                    customer.dob(),
                    customer.proposalDate(),
                    customer.policyNo()
            );

            return stratergies.stream()
                    .flatMap(s -> s.execute(cleansed).stream())
                    .toList();
        };
    }

    private SanctionScreeningStrategy resolveStrategy(String type) {
        return switch (type) {
            case "FIU" ->
                customer -> {
                    NicPair nics = SriLankanNicUtil.resolve(customer.nic());
                    return executor.executeFIU(
                            queryBuilder.buildFIUQuery(
                                    customer.name(),
                                    nics.oldNic(),
                                    nics.newNic()));
                };

            case "FIU-ORG" ->
                customer -> executor.executeFIUOrg(
                queryBuilder.buildFIUOrgQuery(
                customer.name(),
                customer.policyNo()));

            case "consoli" ->
                customer -> executor.executeUN(
                queryBuilder.buildUNQuery(
                customer.name()));

            case "local_watchList" ->
                customer -> {
                    NicPair nics = SriLankanNicUtil.resolve(customer.nic());
                    return executor.executeLocalWatch(
                            queryBuilder.buildLocalWatchListQuery(
                                    customer.name(),
                                    nics.oldNic(),
                                    nics.newNic()));
                };

            default ->
                throw new IllegalArgumentException(
                        "Unknown sanction type: " + type);
        };
    }
}
