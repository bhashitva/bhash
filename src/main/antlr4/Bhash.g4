// Bhash (भाष) v0.1 draft. UTF-8. Generate the Java visitor with ANTLR 4.13.2.
// Parsing recognizes syntax; docs/language-spec.md defines semantic constraints.
// पढ़ो() is a predeclared zero-argument function returning पूर्णांक, not a keyword.
grammar Bhash;

program : functionDecl* EOF ;
functionDecl : FUNCTION IDENTIFIER LPAREN parameters? RPAREN ARROW returnType block ;
parameters : parameter (COMMA parameter)* ;
parameter : IDENTIFIER COLON valueType ;
returnType : valueType | VOID ;
valueType : INT_TYPE | REAL_TYPE | BOOL_TYPE | TEXT_TYPE ;
block : LBRACE statement* RBRACE ;
statement
    : variableDecl
    | assignment
    | ifStatement
    | whileStatement
    | RETURN expression? SEMI
    | BREAK SEMI
    | CONTINUE SEMI
    | PRINT LPAREN arguments? RPAREN SEMI
    | expression SEMI
    | block
    ;
variableDecl : (LET | CONST) IDENTIFIER COLON valueType ASSIGN expression SEMI ;
assignment : IDENTIFIER ASSIGN expression SEMI ;
ifStatement : IF LPAREN expression RPAREN block (ELSE (ifStatement | block))? ;
whileStatement : WHILE LPAREN expression RPAREN block ;
arguments : expression (COMMA expression)* ;

// Layered rules make precedence and left associativity explicit.
expression : logicalOr ;
logicalOr : logicalAnd (OR logicalAnd)* ;
logicalAnd : equality (AND equality)* ;
equality : comparison ((EQ | NE) comparison)* ;
comparison : additive ((LT | LE | GT | GE) additive)* ;
additive : multiplicative ((PLUS | MINUS) multiplicative)* ;
multiplicative : unary ((STAR | SLASH | PERCENT) unary)* ;
unary : (NOT | MINUS | PLUS) unary | primary ;
primary
    : INTEGER_LITERAL
    | REAL_LITERAL
    | STRING_LITERAL
    | TRUE
    | FALSE
    | IDENTIFIER (LPAREN arguments? RPAREN)?
    | LPAREN expression RPAREN
    ;

// Reserve keywords before IDENTIFIER; longer names still use longest match.
FUNCTION : 'कार्य' ;
LET : 'मान' ;
CONST : 'स्थिर' ;
IF : 'यदि' ;
ELSE : 'अन्यथा' ;
WHILE : 'जबतक' ;
RETURN : 'लौटाओ' ;
BREAK : 'विराम' ;
CONTINUE : 'जारी' ;
PRINT : 'लिखो' ;
INT_TYPE : 'पूर्णांक' ;
REAL_TYPE : 'दशमलव' ;
BOOL_TYPE : 'तार्किक' ;
TEXT_TYPE : 'पाठ' ;
VOID : 'शून्य' ;
TRUE : 'सत्य' ;
FALSE : 'असत्य' ;

ARROW : '->' ;
EQ : '==' ; NE : '!=' ; LE : '<=' ; GE : '>=' ;
AND : '&&' ; OR : '||' ; NOT : '!' ;
LT : '<' ; GT : '>' ; ASSIGN : '=' ;
PLUS : '+' ; MINUS : '-' ; STAR : '*' ; SLASH : '/' ; PERCENT : '%' ;
LPAREN : '(' ; RPAREN : ')' ; LBRACE : '{' ; RBRACE : '}' ;
COLON : ':' ; COMMA : ',' ; SEMI : ';' ;

// Each numeric literal uses one digit script; signs are unary operators.
REAL_LITERAL : ASCII_DIGIT+ '.' ASCII_DIGIT+ | DEV_DIGIT+ '.' DEV_DIGIT+ ;
INTEGER_LITERAL : ASCII_DIGIT+ | DEV_DIGIT+ ;
STRING_LITERAL : '"' (ESCAPE | ~["\\\r\n])* '"' ;
fragment ESCAPE : '\\' (["\\nrt] | 'u' HEX HEX HEX HEX) ;
fragment HEX : [0-9a-fA-F] ;
fragment ASCII_DIGIT : [0-9] ;
fragment DEV_DIGIT : [\u0966-\u096F] ;

// Letters start a name; vowel signs, nukta and virama are continuation marks.
// Danda, digits and isolated combining marks cannot start identifiers.
IDENTIFIER : DEV_LETTER (DEV_LETTER | DEV_MARK | ASCII_DIGIT | DEV_DIGIT | '_')* ;
fragment DEV_LETTER : [\u0904-\u0939\u0958-\u0961\u0971-\u097F] ;
fragment DEV_MARK : [\u0900-\u0903\u093A-\u094F\u0951-\u0957\u0962-\u0963] ;

LINE_COMMENT : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
WS : [ \t\r\n\u000C]+ -> skip ;
