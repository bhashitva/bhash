// SPDX-License-Identifier: Apache-2.0
import snippets from '../../../vscode/snippets/bhash.json';
import {keywordEntries,filterKeywords,keywordGroups} from '../../../../playground/src/keyword-picker.mjs';
export {keywordGroups};
const explanations={
  Lambda:'बिना नाम का प्रकारयुक्त फलन बनाएँ।', 'Function value':'फलन को मान में रखकर callback की तरह उपयोग करें।',
  'Main function':'अलग पूर्ण क्रमानुदेश का प्रवेश फलन लिखें।', Function:'नाम, मापदंड और फल का प्रकार देकर फलन बनाएँ।',
  Class:'निर्माता सहित सार्वजनिक वर्ग बनाएँ।', Interface:'वर्गों के लिए अनुबंध घोषित करें।', Record:'अपरिवर्तनीय डेटा का अभिलेख बनाएँ।',
  Enum:'नाम दिए हुए सीमित मान घोषित करें।', Constant:'प्रकार और आरंभिक मान सहित स्थिर बंधन लिखें।', Variable:'बदल सकने वाला स्थानीय मान लिखें।',
  Conditional:'शर्त सत्य होने पर खंड चलाएँ।', 'Collection iteration':'संग्रह के हर तत्व पर चक्र चलाएँ; संग्रह पहले आता है।',
  'Exception handling':'अपवाद पकड़ें और अंत में सफ़ाई करें।', Switch:'मान के अनुसार शाखा चुनें।', Package:'घोषणाओं का संकुल लिखें।',
  Import:'दूसरे संकुल की घोषणा आयात करें।', 'Static method':'वर्ग का साझा फलन बनाएँ।',
  'Resource scope':'हर निकास पर संसाधन बंद करने वाला खंड बनाएँ।', Print:'एक या अधिक मान आउटपुट में लिखें।'
};
const extra=[
  ['While','जबतक','flow','शर्त सत्य रहने तक चक्र चलाएँ।','जबतक (${शर्त}) {\n    ${}\n}'],
  ['If else','यदि / अन्यथा','flow','शर्त के दोनों परिणामों के लिए शाखाएँ लिखें।','यदि (${शर्त}) {\n    ${}\n} अन्यथा {\n    \n}'],
  ['Async function','असमकालिक फलन','async','प्रतीक्षा कर सकने वाला फलन बनाएँ।','असमकालिक फलन ${नाम}() -> शून्य {\n    ${}\n}'],
  ['Await','प्रतीक्षा','async','कार्य का परिणाम असमकालिक रूप से लें।','प्रतीक्षा ${कार्य};'],
  ['Pattern match','मिलान','flow','परिणाम की सफलता और असफलता को अलग सँभालें।','मिलान (${परिणाम}) {\n    विकल्प मानक.परिणाम.सफल(स्थिर मान): { ${} }\n    विकल्प मानक.परिणाम.असफल(स्थिर दोष): { लिखो(दोष); }\n    विकल्प रिक्त: { लिखो("रिक्त परिणाम"); }\n}'],
  ['Tagged union','विकल्पसमूह','objects','अलग-अलग payload वाले सीमित विकल्प घोषित करें।','विकल्पसमूह ${उत्तर} {\n    ${हाँ}(मान: पूर्णांक);\n    नहीं();\n}']
];
// These editor snippets deliberately use only defaults and numbered tab stops.
// Convert them to the same plain editable placeholders as library insertions.
export function editorTemplate(body){
  return (Array.isArray(body)?body.join('\n'):body).replace(/\$\{\d+:([^}]*)\}|\$(\d+)/g,(_all,value)=>'${'+(value??'')+'}');
}
export function languageEntries(words){
  const keywords=keywordEntries(words).map(entry=>({...entry,id:'keyword:'+entry.token,kind:'keywords',template:entry.value}));
  const constructs=Object.entries(snippets).map(([name,snippet])=>{
    if(!explanations[name])throw Error('Missing Hindi construct explanation: '+name);
    const word=keywords.find(entry=>snippet.prefix.includes(entry.value));
    return {id:'construct:'+name,token:name,value:snippet.prefix.at(-1),english:name,aliases:snippet.prefix.join(' '),description:explanations[name],
      group:word?.group||'basics',common:true,kind:'constructs',template:editorTemplate(snippet.body)};
  });
  return [...keywords,...constructs,...extra.map(([name,value,group,description,template])=>({id:'construct:'+name,token:name,value,english:name,group,description,template,common:true,kind:'constructs'}))];
}
export function searchLanguage(entries,query,group='all',kind='all'){
  const found=new Set(filterKeywords(entries.map(entry=>({...entry,english:entry.english+' '+(entry.aliases||'')})),query,group).map(entry=>entry.id));
  return entries.filter(entry=>found.has(entry.id)&&(kind==='all'||entry.kind===kind));
}
