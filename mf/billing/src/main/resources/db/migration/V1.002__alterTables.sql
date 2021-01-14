ALTER TABLE T_BILLS DROP COLUMN VALUE;

ALTER TABLE T_BILLS ADD COLUMN DEFINITION_ID VARCHAR(50) NOT NULL;
ALTER TABLE T_BILLS ADD COLUMN PRICE DECIMAL(9,2) NOT NULL;

CREATE INDEX I_BILLS_CONTRACT_ID ON T_BILLS (CONTRACT_ID);