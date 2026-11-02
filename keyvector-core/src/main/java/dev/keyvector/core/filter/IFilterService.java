package dev.keyvector.core.filter;

import software.amazon.keyspaces.streamsadapter.adapter.KeyspacesStreamsClientRecord;

public interface IFilterService {

    public boolean evaluateFilter(KeyspacesStreamsClientRecord record,String filterExpressionString) ;
}
