declare module '*library-model.mjs' {
    export interface LibraryMember {kind: string; name: string; english: string; description: string; parameters: {name:string;type:string}[]; result:string; shared:boolean; async:boolean;}
    export interface LibraryType {name:string; reference:string; guide:string; typeParameters:string[]; members:LibraryMember[];}
    export interface LibraryEntry {id:string;type:LibraryType;member:LibraryMember|null;}
    export function libraryIndex(catalog: unknown): {types:LibraryType[];entries:LibraryEntry[]};
    export function librarySnippet(type:LibraryType,member?:LibraryMember|null):string;
    export function signature(type:LibraryType,member?:LibraryMember|null):string;
    export function documentationUrl(path:string):string;
}
