// Audit baseline before bhash-devanagari-1. This file is reference data.
IDENTIFIER : DEV_LETTER (DEV_LETTER | DEV_MARK | ASCII_DIGIT | DEV_DIGIT | '_')* ;
fragment ASCII_DIGIT : [0-9] ;
fragment DEV_DIGIT : [\u0966-\u096F] ;
fragment DEV_LETTER : [\u0904-\u0939\u0958-\u0961\u0971-\u097F] ;
fragment DEV_MARK : [\u0900-\u0903\u093A-\u094F\u0951-\u0957\u0962-\u0963] ;
