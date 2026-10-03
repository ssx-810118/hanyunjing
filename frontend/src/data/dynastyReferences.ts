export interface DynastyReference {
  id: string
  title: string
  evidence: string
  description: string
  note: string
  source: string
  sourceUrl: string
}

// Editorial references are separate from product data: a design image is not a relic reconstruction.
// Source review and scope notes: docs/culture-references.md.
export const dynastyReferences: Record<string, readonly DynastyReference[]> = {
  汉: [
    {
      id: 'han-curved-gauze',
      title: '曲裾素纱襌衣',
      evidence: '西汉 · 出土实物',
      description: '出土于长沙马王堆一号汉墓，现藏湖南博物院。曲裾款的衣长与结构有别于同墓出土的直裾款，是认识西汉丝织服饰的实物参考。',
      note: '襌（dān）指无衬里的单衣；不将上方现代曲裾设计图直接等同于这件文物，也不据此推定所有汉代人的穿着。',
      source: '《国家人文历史》：素纱襌衣——西汉顶级“织造”（湖南博物院研究人员访谈及藏品资料）',
      sourceUrl: 'https://www.gjrwls.com/jinghua/20250613/1118271975198818304.html'
    },
    {
      id: 'han-straight-gauze',
      title: '直裾素纱襌衣',
      evidence: '西汉 · 出土实物',
      description: '与曲裾款同出马王堆一号汉墓，现藏湖南博物院。衣料为轻薄素纱，可与曲裾款对照认识汉代丝织工艺及不同衣裾结构。',
      note: '两件文物应分别辨识；具体穿着层次与用途仍须结合研究，不凭颜色、轻薄程度或现代商品名称断定礼仪用途。',
      source: '《国家人文历史》：素纱襌衣——西汉顶级“织造”（湖南博物院研究人员访谈及藏品资料）',
      sourceUrl: 'https://www.gjrwls.com/jinghua/20250613/1118271975198818304.html'
    }
  ],
  唐: [
    {
      id: 'tang-skirt-and-banbi',
      title: '高腰裙、半臂与披帛',
      evidence: '唐 · 出土陶俑中的服饰',
      description: '中国国家博物馆介绍的三彩釉陶女俑，1957年出土于西安土门村：小袖上衣内见半臂，长裙束于胸前，肩后垂有帔帛，为理解唐代女子裙装搭配提供图像实物。',
      note: '这是陶俑表现的服饰组合，并非一套留存至今的丝织衣物；唐代前后期、地域与场合的穿着并不完全相同。',
      source: '中国国家博物馆：《长安水边多丽人》',
      sourceUrl: 'https://www.chnmuseum.cn/yj/xscg/xslw/201812/t20181224_33161.shtml'
    },
    {
      id: 'tang-round-collar-robe',
      title: '圆领袍与幞头',
      evidence: '唐 · 陶俑中的服饰',
      description: '中国国家博物馆《陶男俑》藏品说明，将俑上圆领袍、幞头和靴联系到唐代常服，展示了与宽袍礼服有别的一类穿着形象。',
      note: '圆领袍并非唐代独有，后世仍有发展；不能只凭圆领或衣服颜色，就认定某一件衣服的朝代与身份。',
      source: '中国国家博物馆：《陶男俑》',
      sourceUrl: 'https://www.chnmuseum.cn/zp/zpml/kgfjp/202111/t20211116_252268.shtml'
    }
  ],
  宋: [
    {
      id: 'song-bordered-beizi',
      title: '缠枝花纹罗镶边褙子',
      evidence: '南宋 · 出土实物',
      description: '江西德安周氏墓出土，中国丝绸博物馆曾据此开展结构与工艺研习。这件夹衣以罗为面、绢为里，门襟以纽襻系结，侧面开衩。',
      note: '这里介绍的是特定墓葬出土褙子；本站宋风长衫属于现代设计参考，不直接宣称复原了这件实物。',
      source: '中国丝绸博物馆：《南宋褙子、围裹式两片裙的裁剪与制作》',
      sourceUrl: 'https://www.chinasilkmuseum.com/yg/info_13.aspx?itemid=31106'
    },
    {
      id: 'song-two-panel-skirt',
      title: '穿枝飞鸟纹围裹式两片裙',
      evidence: '南宋 · 出土实物',
      description: '同出江西德安周氏墓。馆方介绍其为单裙，罗质裙身配绢质裙腰，两块裙片围裹交叠，可作为认识南宋裙装裁剪方式的实例。',
      note: '“两片”说明这件裙的结构，不能据此将所有两片裙或现代马面裙都直接归为同一种南宋形制。',
      source: '中国丝绸博物馆：《南宋褙子、围裹式两片裙的裁剪与制作》',
      sourceUrl: 'https://www.chinasilkmuseum.com/yg/info_13.aspx?itemid=31106'
    }
  ],
  元: [
    {
      id: 'yuan-braided-waist-robe',
      title: '辫线袍',
      evidence: '元 · 馆藏实物 / 蒙古服饰背景',
      description: '中国丝绸博物馆藏《辫线袍》定为元代，馆方记述其交领、右衽、窄袖，腰间钉缀辫线、下摆较宽；这类袍服与北方骑射生活及蒙古服饰传统有关。',
      note: '元代服饰包含多民族传统，不将辫线袍一概表述为汉族形制；此馆藏也不能与本站赭金设计图等同。',
      source: '中国丝绸博物馆：馆藏《辫线袍》',
      sourceUrl: 'https://www.chinasilkmuseum.com/zggd/info_21.aspx?itemid=1848'
    },
    {
      id: 'yuan-wide-sleeved-robe',
      title: '对鹰纹织金锦大袖袍',
      evidence: '元 · 文物修复记录',
      description: '中国丝绸博物馆“元代织金锦服饰修复”项目列有对鹰纹织金锦大袖袍，与辫线袍分别记述，说明元代服饰实物并不只有窄袖、束腰的一种样貌。',
      note: '依据馆方修复记录介绍文物名称与年代，不据“大袖”推定穿着者民族、性别或礼仪等级。',
      source: '中国丝绸博物馆：《修复工作汇总》之“元代织金锦服饰修复”',
      sourceUrl: 'https://www.chinasilkmuseum.com/xscg/info_28.aspx?itemid=4312'
    }
  ],
  明: [
    {
      id: 'ming-yesa',
      title: '曲水如意云纹暗花缎曳撒袍',
      evidence: '明 · 馆藏实物',
      description: '中国丝绸博物馆将此袍定为明代。其交领右衽，腰下打褶形成裙状下摆；馆方指出，这类服装受到元代蒙古辫线袄的影响。',
      note: '本站“苍青曳撒”归入明代设计参考，不归为唐制；形制间的传承影响，不等于将这件明代藏品改称元代。',
      source: '中国丝绸博物馆：馆藏《明代曲水如意云纹暗花缎曳撒袍》',
      sourceUrl: 'https://www.chinasilkmuseum.com/zggd/info_21.aspx?itemid=1900'
    },
    {
      id: 'ming-layered-blouse-skirt',
      title: '明初衫裙组合',
      evidence: '明 · 馆藏实物',
      description: '中国丝绸博物馆藏《绢短袖衫＆绮长袖衫＆绢裙》，介绍了一套明初女子服装：外穿短袖衫，内配长袖衫，下着有褶的绢裙，提供了分层穿搭的实物参考。',
      note: '沿用馆方对具体藏品的定名；这套衫裙不能直接作为本站月白袄马面裙的文物复原证明。',
      source: '中国丝绸博物馆：馆藏《绢短袖衫＆绮长袖衫＆绢裙》',
      sourceUrl: 'https://www.chinasilkmuseum.com/zggd/info_21.aspx?itemid=1870'
    }
  ]
}
