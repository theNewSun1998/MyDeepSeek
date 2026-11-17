import { Theme } from '../common/Theme';

/** 设置页的一行文本输入 */
@Component
export struct SettingField {
  @Prop label: string = '';
  @Prop value: string = '';
  @Prop placeholder: string = '';
  @Prop secret: boolean = false;
  onChange: (v: string) => void = () => {};

  build() {
    Column({ space: 6 }) {
      Text(this.label)
        .fontSize(13)
        .fontColor(Theme.TEXT_SUB)
        .width('100%')
      TextInput({ text: this.value, placeholder: this.placeholder })
        .type(this.secret ? InputType.Password : InputType.Normal)
        .fontSize(14)
        .backgroundColor(Theme.BG_GRAY)
        .borderRadius(12)
        .onChange((v: string) => {
          this.onChange(v);
        })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }
}

/** 设置页的一行开关 */
@Component
export struct SettingSwitch {
  @Prop label: string = '';
  @Prop isOn: boolean = false;
  onChange: (v: boolean) => void = () => {};

  build() {
    Row() {
      Text(this.label)
        .fontSize(14)
        .fontColor(Theme.TEXT)
        .layoutWeight(1)
      Toggle({ type: ToggleType.Switch, isOn: this.isOn })
        .selectedColor(Theme.BRAND)
        .onChange((v: boolean) => {
          this.onChange(v);
        })
    }
    .width('100%')
  }
}

/** 设置页的一行下拉选择 */
@Component
export struct SettingSelect {
  @Prop label: string = '';
  @Prop options: Array<string> = [];
  @Prop selected: number = 0;
  onSelect: (i: number, v: string) => void = () => {};

  private buildOptions(): Array<SelectOption> {
    const out: Array<SelectOption> = [];
    for (let i = 0; i < this.options.length; i++) {
      const opt: SelectOption = { value: this.options[i] };
      out.push(opt);
    }
    return out;
  }

  build() {
    Row() {
      Text(this.label)
        .fontSize(14)
        .fontColor(Theme.TEXT)
        .layoutWeight(1)
      Select(this.buildOptions())
        .selected(this.selected)
        .value(this.options.length > this.selected ? this.options[this.selected] : '')
        .font({ size: 13 })
        .selectedOptionFont({ size: 13 })
        .optionFont({ size: 13 })
        .onSelect((index: number, value: string) => {
          this.onSelect(index, value);
        })
    }
    .width('100%')
  }
}

/** 分组小标题 */
@Component
export struct SettingSectionTitle {
  @Prop title: string = '';

  build() {
    Text(this.title)
      .fontSize(12)
      .fontColor(Theme.TEXT_FAINT)
      .width('100%')
      .padding({ top: 6 })
  }
}
