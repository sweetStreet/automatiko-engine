package io.automatik.engine.addons.persistence.db;

import javax.transaction.UserTransaction;

import io.automatik.engine.api.event.EventManager;
import io.automatik.engine.api.uow.UnitOfWork;
import io.automatik.engine.api.uow.UnitOfWorkFactory;

public class TransactionalUnitOfWorkFactory implements UnitOfWorkFactory {

    private UserTransaction transaction;

    public TransactionalUnitOfWorkFactory(UserTransaction transaction) {
        this.transaction = transaction;
    }

    @Override
    public UnitOfWork create(EventManager eventManager) {
        return new TransactionalUnitOfWork(eventManager, transaction);
    }

}
