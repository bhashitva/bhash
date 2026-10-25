// SPDX-License-Identifier: Apache-2.0
// Bhash (भाष) v0.7 core. UTF-8. Generate the Java visitor with ANTLR 4.13.2.
// Parsing recognizes syntax; docs/language-spec.md defines semantic constraints.
// पढ़ो() is a predeclared zero-argument function returning पूर्णांक, not a keyword.
grammar Bhash;

@parser::members {
    // Keep angle brackets separate for nested generics, but require contiguous shift characters.
    private boolean adjacentShift(int count) {
        for (int i = 1; i < count; i++)
            if (_input.LT(i).getStopIndex() + 1 != _input.LT(i + 1).getStartIndex()) return false;
        return true;
    }
    // Expression type arguments must lead to a call or static receiver. Otherwise
    // a < b >> count is a comparison with a shift, rather than a<b> > count.
    private boolean genericPrimaryAhead() {
        if (_input.LA(1) != LT) return false;
        int depth = 0;
        for (int i = 1; ; i++) {
            int token = _input.LA(i);
            if (token == EOF) return false;
            if (token == LT) depth++;
            else if (token == GT) {
                if (--depth == 0) return _input.LA(i + 1) == LPAREN || _input.LA(i + 1) == DOT;
            } else if (token != IDENTIFIER && token != COMMA && token != DOT
                    && token != INT_TYPE && token != REAL_TYPE && token != BOOL_TYPE && token != TEXT_TYPE
                    && token != LBRACK && token != RBRACK)
                return false;
        }
    }
}

program : packageDecl? importDecl* (PUBLIC? (functionDecl | classDecl | interfaceDecl))* EOF ;
packageDecl : PACKAGE qualifiedName SEMI ;
importDecl : IMPORT qualifiedName (AS IDENTIFIER)? SEMI ;
qualifiedName : IDENTIFIER (DOT IDENTIFIER)* ;
classDecl : CLASS IDENTIFIER typeParameters? (EXTENDS valueType)? (IMPLEMENTS typeList)? LBRACE classMember* RBRACE ;
interfaceDecl : INTERFACE IDENTIFIER typeParameters? (EXTENDS typeList)? LBRACE functionDecl* RBRACE ;
typeParameters : LT IDENTIFIER (COMMA IDENTIFIER)* GT ;
typeArguments : LT valueType (COMMA valueType)* GT ;
typeList : valueType (COMMA valueType)* ;
classMember : visibility? STATIC? (fieldDecl | constructorDecl | OVERRIDE? functionDecl) ;
visibility : PUBLIC | PRIVATE | PROTECTED ;
fieldDecl : (LET | CONST) IDENTIFIER COLON valueType (ASSIGN expression)? SEMI ;
constructorDecl : CONSTRUCTOR LPAREN parameters? RPAREN (COLON BASE LPAREN arguments? RPAREN)? block ;
functionDecl : FUNCTION IDENTIFIER typeParameters? LPAREN parameters? RPAREN ARROW returnType (block | SEMI) ;
parameters : parameter (COMMA parameter)* ;
parameter : IDENTIFIER COLON valueType ;
returnType : valueType | VOID ;
valueType : (INT_TYPE | REAL_TYPE | BOOL_TYPE | TEXT_TYPE | IDENTIFIER typeQualifier* typeArguments?) arraySuffix* ;
arraySuffix : LBRACK RBRACK ;
arrayDimension : LBRACK expression RBRACK ;
collectionInitializer : LBRACE (mapEntry (COMMA mapEntry)* | arguments)? RBRACE ;
mapEntry : expression COLON expression ;
typeQualifier : DOT IDENTIFIER ;
block : LBRACE statement* RBRACE ;
statement
    : variableDecl
    | assignment
    | ifStatement
    | whileStatement
    | foreachStatement
    | switchStatement
    | RETURN expression? SEMI
    | BREAK SEMI
    | CONTINUE SEMI
    | PRINT LPAREN arguments? RPAREN SEMI
    | expression SEMI
    | block
    ;
variableDecl : (LET | CONST) IDENTIFIER COLON valueType ASSIGN expression SEMI ;
assignment : postfix ASSIGN expression SEMI ;
ifStatement : IF LPAREN expression RPAREN block (ELSE (ifStatement | block))? ;
foreachStatement : FOREACH LPAREN parameter (COMMA parameter)? IN expression RPAREN block ;
whileStatement : WHILE LPAREN expression RPAREN block ;
switchStatement : SWITCH LPAREN expression RPAREN LBRACE switchSection* RBRACE ;
switchSection : (CASE caseValue COLON)+ statement* | DEFAULT COLON statement* ;
caseValue : (PLUS | MINUS)? INTEGER_LITERAL | STRING_LITERAL | TRUE | FALSE ;
arguments : expression (COMMA expression)* ;

// Layered rules make precedence and left associativity explicit.
expression : logicalOr ;
logicalOr : logicalAnd (OR logicalAnd)* ;
logicalAnd : bitwiseOr (AND bitwiseOr)* ;
bitwiseOr : bitwiseXor (BIT_OR bitwiseXor)* ;
bitwiseXor : bitwiseAnd (BIT_XOR bitwiseAnd)* ;
bitwiseAnd : equality (BIT_AND equality)* ;
equality : comparison ((EQ | NE) comparison)* ;
comparison : shift ((LT | LE | GT | GE) shift)* ;
shift : additive (shiftOperator additive)* ;
shiftOperator
    : {adjacentShift(2)}? LT LT
    | {adjacentShift(3)}? GT GT GT
    | {adjacentShift(2)}? GT GT
    ;
additive : multiplicative ((PLUS | MINUS) multiplicative)* ;
multiplicative : unary ((STAR | SLASH | PERCENT) unary)* ;
unary : (NOT | BIT_NOT | MINUS | PLUS) unary | postfix ;
postfix : primary postfixPart* ;
postfixPart : DOT IDENTIFIER ({genericPrimaryAhead()}? typeArguments)? (LPAREN arguments? RPAREN)? | LBRACK expression RBRACK ;
primary
    : INTEGER_LITERAL
    | REAL_LITERAL
    | STRING_LITERAL
    | TRUE
    | FALSE
    | NULL
    | THIS
    | BASE
    | NEW valueType (LPAREN arguments? RPAREN | arrayDimension+ arraySuffix* | collectionInitializer)
    | IDENTIFIER ({genericPrimaryAhead()}? typeArguments)? (LPAREN arguments? RPAREN)?
    | LPAREN expression RPAREN
    ;

// Reserve keywords before IDENTIFIER; longer names still use longest match.
PACKAGE : 'संकुल' ;
IMPORT : 'आयात' ;
AS : 'उपनाम' ;
FUNCTION : 'कार्य' ;
CLASS : 'वर्ग' ;
INTERFACE : 'अनुबंध' ;
IMPLEMENTS : 'पालन' ;
STATIC : 'साझा' ;
EXTENDS : 'विस्तार' ;
CONSTRUCTOR : 'निर्माता' ;
NEW : 'नया' ;
THIS : 'यह' ;
BASE : 'आधार' ;
PUBLIC : 'सार्वजनिक' ;
PRIVATE : 'निजी' ;
PROTECTED : 'संरक्षित' ;
OVERRIDE : 'अधिलेखन' ;
NULL : 'रिक्त' ;
LET : 'मान' ;
CONST : 'स्थिर' ;
IF : 'यदि' ;
ELSE : 'अन्यथा' ;
WHILE : 'जबतक' ;
FOREACH : 'प्रत्येक' ;
IN : 'में' ;
SWITCH : 'चयन' ;
CASE : 'विकल्प' ;
DEFAULT : 'शेष' ;
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
BIT_AND : '&' ; BIT_OR : '|' ; BIT_XOR : '^' ; BIT_NOT : '~' ;
LT : '<' ; GT : '>' ; ASSIGN : '=' ;
PLUS : '+' ; MINUS : '-' ; STAR : '*' ; SLASH : '/' ; PERCENT : '%' ;
LPAREN : '(' ; RPAREN : ')' ; LBRACE : '{' ; RBRACE : '}' ;
COLON : ':' ; COMMA : ',' ; SEMI : ';' ;
DOT : '.' ;
LBRACK : '[' ; RBRACK : ']' ;

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
